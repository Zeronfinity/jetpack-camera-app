/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jetpackcamera.domain.camera.test

import android.util.Range
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionInfo
import androidx.camera.core.impl.Config
import androidx.camera.core.impl.UseCaseConfig
import androidx.camera.core.impl.UseCaseConfigFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.usecases.PreviewUseCase
import androidx.camera.testing.impl.fakes.FakeUseCaseConfig
import java.util.concurrent.Executor

class FakePreviewUseCase(
    private val config: UseCaseConfig<*> = FakeUseCaseConfig.Builder().useCaseConfig
) : PreviewUseCase(config) {
    override fun getDefaultConfig(
        applyDefaultConfig: Boolean,
        factory: UseCaseConfigFactory
    ) = config

    override fun getUseCaseConfigBuilder(config: Config) = FakeUseCaseConfig.Builder()

    override fun getDynamicRange(): DynamicRange {
        TODO("Not yet implemented")
    }

    override fun getResolutionInfo(): ResolutionInfo? {
        TODO("Not yet implemented")
    }

    override fun getResolutionSelector(): ResolutionSelector? {
        TODO("Not yet implemented")
    }

    override fun getTargetFrameRate(): Range<Int> {
        TODO("Not yet implemented")
    }

    override fun getTargetRotation(): Int {
        TODO("Not yet implemented")
    }

    override fun isPreviewStabilizationEnabled(): Boolean {
        TODO("Not yet implemented")
    }

    override fun setSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        TODO("Not yet implemented")
    }

    override fun setSurfaceProvider(executor: Executor, surfaceProvider: Preview.SurfaceProvider?) {
        TODO("Not yet implemented")
    }

    override fun setTargetRotation(targetRotation: Int) {
        TODO("Not yet implemented")
    }
}
