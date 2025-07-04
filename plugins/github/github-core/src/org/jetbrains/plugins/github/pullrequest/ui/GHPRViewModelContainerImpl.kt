// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.pullrequest.ui

import com.intellij.collaboration.async.collectScoped
import com.intellij.collaboration.async.launchNow
import com.intellij.collaboration.async.mapScoped
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModelBase
import com.intellij.collaboration.ui.util.selectedItem
import com.intellij.collaboration.util.ChangesSelection
import com.intellij.collaboration.util.getOrNull
import com.intellij.openapi.project.Project
import com.intellij.platform.util.coroutines.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.plugins.github.ai.GHPRAIReviewExtension
import org.jetbrains.plugins.github.ai.GHPRAIReviewViewModel
import org.jetbrains.plugins.github.ai.GHPRAISummaryExtension
import org.jetbrains.plugins.github.ai.GHPRAISummaryViewModel
import org.jetbrains.plugins.github.pullrequest.config.GithubPullRequestsProjectUISettings
import org.jetbrains.plugins.github.pullrequest.data.GHPRDataContext
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import org.jetbrains.plugins.github.pullrequest.data.provider.GHPRDataProvider
import org.jetbrains.plugins.github.pullrequest.ui.comment.GHPRThreadsViewModelsImpl
import org.jetbrains.plugins.github.pullrequest.ui.diff.GHPRDiffViewModel
import org.jetbrains.plugins.github.pullrequest.ui.diff.GHPRDiffViewModelImpl
import org.jetbrains.plugins.github.pullrequest.ui.editor.GHPRReviewInEditorViewModel
import org.jetbrains.plugins.github.pullrequest.ui.editor.GHPRReviewInEditorViewModelImpl
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRBranchWidgetViewModel
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRBranchWidgetViewModelImpl
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRReviewViewModelHelper
import org.jetbrains.plugins.github.pullrequest.ui.timeline.GHPRTimelineViewModel
import org.jetbrains.plugins.github.pullrequest.ui.timeline.GHPRTimelineViewModelImpl
import org.jetbrains.plugins.github.pullrequest.ui.toolwindow.model.GHPRInfoViewModel

@ApiStatus.Internal
internal interface GHPRViewModelContainer {
  val aiReviewVm: StateFlow<GHPRAIReviewViewModel?>
  val aiSummaryVm: StateFlow<GHPRAISummaryViewModel?>

  val infoVm: GHPRInfoViewModel
  val branchWidgetVm: GHPRBranchWidgetViewModel
  val diffVm: GHPRDiffViewModel
  val editorVm: GHPRReviewInEditorViewModel
  val timelineVm: GHPRTimelineViewModel
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class GHPRViewModelContainerImpl(
  project: Project,
  parentCs: CoroutineScope,
  dataContext: GHPRDataContext,
  private val pullRequestId: GHPRIdentifier,
  private val viewPullRequest: (GHPRIdentifier) -> Unit,
  private val viewPullRequestOnCommit: (GHPRIdentifier, String) -> Unit,
  private val openPullRequestDiff: (GHPRIdentifier?, Boolean) -> Unit,
  private val refreshPrOnCurrentBranch: () -> Unit,
) : GHPRViewModelContainer {
  private val cs = parentCs.childScope(javaClass.name)

  private val dataProvider: GHPRDataProvider = dataContext.dataProviderRepository.getDataProvider(pullRequestId, cs)

  private val diffSelectionRequests = MutableSharedFlow<ChangesSelection>(1)

  private val lazyInfoVm = lazy {
    GHPRInfoViewModel(project, cs, dataContext, dataProvider, openPullRequestDiff).apply {
      setup()
    }
  }
  override val infoVm: GHPRInfoViewModel by lazyInfoVm
  private val reviewVmHelper: GHPRReviewViewModelHelper by lazy { GHPRReviewViewModelHelper(cs, dataProvider) }

  override val aiReviewVm: StateFlow<GHPRAIReviewViewModel?> =
    GHPRAIReviewExtension.singleFlow
      .mapScoped { it?.provideReviewVm(project, this, dataContext, dataProvider) }
      .stateIn(cs, SharingStarted.Eagerly, null)
  override val aiSummaryVm: StateFlow<GHPRAISummaryViewModel?> =
    GHPRAISummaryExtension.singleFlow
      .mapScoped { extension ->
        val cs = this@mapScoped
        extension?.provideSummaryVm(cs, project, dataContext, dataProvider)
      }
      .stateIn(cs, SharingStarted.Eagerly, null)

  private val branchStateVm by lazy {
    GHPRReviewBranchStateSharedViewModel(cs, dataContext, dataProvider)
  }
  private val settings = GithubPullRequestsProjectUISettings.getInstance(project)
  override val branchWidgetVm: GHPRBranchWidgetViewModel by lazy {
    GHPRBranchWidgetViewModelImpl(project, cs, settings, dataProvider, branchStateVm, reviewVmHelper, pullRequestId, viewPullRequest)
  }

  private val threadsVms = GHPRThreadsViewModelsImpl(project, cs, dataContext, dataProvider)
  override val diffVm: GHPRDiffViewModel by lazy {
    GHPRDiffViewModelImpl(project, cs, dataContext, dataProvider, reviewVmHelper, threadsVms).apply {
      setup()
    }
  }

  override val editorVm: GHPRReviewInEditorViewModel by lazy {
    GHPRReviewInEditorViewModelImpl(project, cs, settings, dataContext, dataProvider, branchStateVm, threadsVms) {
      diffSelectionRequests.tryEmit(it)
      openPullRequestDiff(pullRequestId, true)
    }
  }

  override val timelineVm: GHPRTimelineViewModel by lazy {
    GHPRTimelineViewModelImpl(project, cs, dataContext, dataProvider).apply {
      setup()
    }
  }

  init {
    cs.launchNow {
      dataProvider.detailsData.stateChangeSignal.collectLatest {
        refreshPrOnCurrentBranch()
      }
    }
  }

  private fun GHPRInfoViewModel.setup() {
    cs.launchNow {
      detailsVm.flatMapLatest { detailsVmResult ->
        detailsVmResult.getOrNull()?.changesVm?.changeListVm ?: flowOf(null)
      }.map {
        it?.getOrNull() as? CodeReviewChangeListViewModelBase
      }.collectScoped { vm ->
        vm?.handleSelection {
          if (it != null) {
            diffSelectionRequests.tryEmit(it)
          }
        }
      }
    }
  }

  private fun GHPRDiffViewModelImpl.setup() {
    cs.launchNow {
      diffSelectionRequests.collect {
        showDiffFor(it)
      }
    }

    cs.launchNow {
      handleSelection {
        val change = it?.selectedItem ?: return@handleSelection
        if (lazyInfoVm.isInitialized()) {
          lazyInfoVm.value.detailsVm.value.getOrNull()?.changesVm?.selectChange(change)
        }
      }
    }
  }

  private fun GHPRTimelineViewModelImpl.setup() {
    cs.launchNow {
      showCommitRequests.collect {
        viewPullRequestOnCommit(pullRequestId, it)
      }
    }

    cs.launchNow {
      showDiffRequests.collect {
        diffVm.showDiffFor(it)
        openPullRequestDiff(pullRequestId, true)
      }
    }
  }
}