// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.gradle.multiplatformTests.workspace

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.gradle.multiplatformTests.KotlinTestProperties
import org.jetbrains.kotlin.gradle.multiplatformTests.TestConfiguration
import java.io.File

data class PrinterContext(
    val project: Project,
    val projectRoot: File,
    val testConfiguration: TestConfiguration,
    val testProperties: KotlinTestProperties,
)
