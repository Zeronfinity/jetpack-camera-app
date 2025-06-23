/*
 * Copyright (C) 2023 The Android Open Source Project
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
@file:OptIn(ExperimentalSessionConfig::class)

package com.google.jetpackcamera.core.camera

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange as CXDynamicRange
import androidx.camera.core.ExperimentalSessionConfig
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.core.featuregroup.GroupableFeature.Companion.HDR_HLG10
import androidx.camera.core.featuregroup.GroupableFeature.Companion.IMAGE_ULTRA_HDR
import androidx.camera.core.featuregroup.GroupableFeature.Companion.PREVIEW_STABILIZATION
import androidx.camera.core.takePicture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.Recorder
import com.google.jetpackcamera.core.camera.DebugCameraInfoUtil.getAllCamerasPropertiesJSONArray
import com.google.jetpackcamera.core.camera.DebugCameraInfoUtil.writeFileExternalStorage
import com.google.jetpackcamera.core.common.DefaultDispatcher
import com.google.jetpackcamera.core.common.IODispatcher
import com.google.jetpackcamera.settings.SettableConstraintsRepository
import com.google.jetpackcamera.settings.model.AspectRatio
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.CameraConstraints
import com.google.jetpackcamera.settings.model.CameraConstraints.Companion.FPS_15
import com.google.jetpackcamera.settings.model.CameraConstraints.Companion.FPS_60
import com.google.jetpackcamera.settings.model.CameraZoomRatio
import com.google.jetpackcamera.settings.model.CaptureMode
import com.google.jetpackcamera.settings.model.ConcurrentCameraMode
import com.google.jetpackcamera.settings.model.DeviceRotation
import com.google.jetpackcamera.settings.model.DynamicRange
import com.google.jetpackcamera.settings.model.FlashMode
import com.google.jetpackcamera.settings.model.Illuminant
import com.google.jetpackcamera.settings.model.ImageOutputFormat
import com.google.jetpackcamera.settings.model.LensFacing
import com.google.jetpackcamera.settings.model.LensToZoom
import com.google.jetpackcamera.settings.model.StabilizationMode
import com.google.jetpackcamera.settings.model.StreamConfig
import com.google.jetpackcamera.settings.model.SystemConstraints
import com.google.jetpackcamera.settings.model.VideoQuality
import com.google.jetpackcamera.settings.model.ZoomChange
import com.google.jetpackcamera.settings.model.forCurrentLens
import dagger.hilt.android.scopes.ViewModelScoped
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private const val TAG = "CameraXCameraUseCase"
const val TARGET_FPS_AUTO = 0
const val TARGET_FPS_15 = 15
const val TARGET_FPS_30 = 30
const val TARGET_FPS_60 = 60

const val UNLIMITED_VIDEO_DURATION = 0L

/**
 * CameraX based implementation for [CameraUseCase]
 */
@ViewModelScoped
class CameraXCameraUseCase
@Inject
constructor(
    private val application: Application,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @IODispatcher private val iODispatcher: CoroutineDispatcher,
    private val constraintsRepository: SettableConstraintsRepository
) : CameraUseCase {
    private lateinit var cameraProvider: ProcessCameraProvider

    private var imageCaptureUseCase: ImageCapture? = null

    private lateinit var systemConstraints: SystemConstraints

    private val screenFlashEvents: Channel<CameraUseCase.ScreenFlashEvent> =
        Channel(capacity = Channel.UNLIMITED)
    private val focusMeteringEvents =
        Channel<CameraEvent.FocusMeteringEvent>(capacity = Channel.CONFLATED)
    private val videoCaptureControlEvents = Channel<VideoCaptureControlEvent>()

    private val currentSettings = MutableStateFlow<CameraAppSettings?>(null)

    private val zoomChanges = MutableStateFlow<CameraZoomRatio?>(null)

    // Could be improved by setting initial value only when camera is initialized
    private var currentCameraState = MutableStateFlow(CameraState())
    override fun getCurrentCameraState(): StateFlow<CameraState> = currentCameraState.asStateFlow()

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)

    override fun getSurfaceRequest(): StateFlow<SurfaceRequest?> = _surfaceRequest.asStateFlow()

    override suspend fun initialize(
        cameraAppSettings: CameraAppSettings,
        isDebugMode: Boolean,
        cameraPropertiesJSONCallback: (result: String) -> Unit
    ) {
        cameraProvider = ProcessCameraProvider.awaitInstance(application)

        // updates values for available cameras
        val availableCameraLenses =
            listOf(
                LensFacing.FRONT,
                LensFacing.BACK
            ).filter {
                cameraProvider.hasCamera(it.toCameraSelector())
            }

        // Build and update the system constraints
        systemConstraints = SystemConstraints(
            availableLenses = availableCameraLenses,
            concurrentCamerasSupported = cameraProvider.availableConcurrentCameraInfos.any {
                it.map { cameraInfo -> cameraInfo.appLensFacing }
                    .toSet() == setOf(LensFacing.FRONT, LensFacing.BACK)
            },
            perLensConstraints = buildMap {
                val availableCameraInfos = cameraProvider.availableCameraInfos
                for (lensFacing in availableCameraLenses) {
                    val selector = lensFacing.toCameraSelector()
                    selector.filter(availableCameraInfos).firstOrNull()?.let { camInfo ->
                        put(lensFacing, createCameraConstraints(camInfo, lensFacing))
                    }
                }
            }
        )

        constraintsRepository.updateSystemConstraints(systemConstraints)

        currentSettings.value =
            cameraAppSettings
                .tryApplyDynamicRangeConstraints()
                .tryApplyAspectRatioForExternalCapture(cameraAppSettings.captureMode)
                .tryApplyImageFormatConstraints()
                .tryApplyFrameRateConstraints()
                .tryApplyStabilizationConstraints()
                .tryApplyConcurrentCameraModeConstraints()
                .tryApplyFlashModeConstraints()
                .tryApplyCaptureModeConstraints()
                .tryApplyVideoQualityConstraints()
                .tryApplyCaptureModeConstraints()

        if (isDebugMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            withContext(iODispatcher) {
                val cameraPropertiesJSON =
                    getAllCamerasPropertiesJSONArray(cameraProvider.availableCameraInfos).toString()
                val fileDir = File(application.getExternalFilesDir(null), "Debug")
                fileDir.mkdirs()
                val file = File(
                    fileDir,
                    "JCACameraProperties.json"
                )
                writeFileExternalStorage(file, cameraPropertiesJSON)
                cameraPropertiesJSONCallback.invoke(cameraPropertiesJSON)
                Log.d(TAG, "JCACameraProperties written to ${file.path}. \n$cameraPropertiesJSON")
            }
        }
    }

    private fun createCameraConstraints(
        camInfo: CameraInfo,
        lensFacing: LensFacing
    ): CameraConstraints {
        val videoCapabilities = Recorder.getVideoCapabilities(camInfo)
        val supportedDynamicRanges =
            videoCapabilities.supportedDynamicRanges
                .mapNotNull(CXDynamicRange::toSupportedAppDynamicRange)
                .toSet()
        val supportedVideoQualitiesMap =
            buildMap {
                for (dynamicRange in supportedDynamicRanges) {
                    val supportedVideoQualities =
                        videoCapabilities.getSupportedQualities(
                            dynamicRange.toCXDynamicRange()
                        ).map { it.toVideoQuality() }
                    put(dynamicRange, supportedVideoQualities)
                }
            }
        val zoomState = camInfo.zoomState.value
        val supportedZoomRange: Range<Float>? =
            zoomState?.let { Range(it.minZoomRatio, it.maxZoomRatio) }

        val supportedStabilizationModes = buildSet {
            if (camInfo.isPreviewStabilizationSupported) {
                add(StabilizationMode.ON)
                add(StabilizationMode.AUTO)
            }

            if (camInfo.isVideoStabilizationSupported) {
                add(StabilizationMode.HIGH_QUALITY)
            }

            if (camInfo.isOpticalStabilizationSupported) {
                add(StabilizationMode.OPTICAL)
                add(StabilizationMode.AUTO)
            }

            add(StabilizationMode.OFF)
        }

        val unsupportedStabilizationFpsMap = buildMap {
            for (stabilizationMode in supportedStabilizationModes) {
                when (stabilizationMode) {
                    StabilizationMode.ON -> setOf(FPS_15, FPS_60)
                    StabilizationMode.HIGH_QUALITY -> setOf(FPS_60)
                    StabilizationMode.OPTICAL -> emptySet()
                    else -> null
                }?.let { put(stabilizationMode, it) }
            }
        }

        val supportedFixedFrameRates =
            camInfo.filterSupportedFixedFrameRates(FIXED_FRAME_RATES)
        val supportedImageFormats = camInfo.supportedImageFormats
        val supportedIlluminants = buildSet {
            if (camInfo.hasFlashUnit()) {
                add(Illuminant.FLASH_UNIT)
            }

            if (lensFacing == LensFacing.FRONT) {
                add(Illuminant.SCREEN)
            }

            if (camInfo.isLowLightBoostSupported) {
                add(Illuminant.LOW_LIGHT_BOOST)
            }
        }

        val supportedFlashModes = buildSet {
            add(FlashMode.OFF)
            if ((
                    setOf(
                        Illuminant.FLASH_UNIT,
                        Illuminant.SCREEN
                    ) intersect supportedIlluminants
                    ).isNotEmpty()
            ) {
                add(FlashMode.ON)
                add(FlashMode.AUTO)
            }

            if (Illuminant.LOW_LIGHT_BOOST in supportedIlluminants) {
                add(FlashMode.LOW_LIGHT_BOOST)
            }
        }

        return CameraConstraints(
            supportedStabilizationModes = supportedStabilizationModes,
            supportedFixedFrameRates = supportedFixedFrameRates,
            supportedDynamicRanges = supportedDynamicRanges,
            supportedImageFormatsMap = mapOf(
                // Only JPEG is supported in single-stream mode, since
                // single-stream mode uses CameraEffect, which does not support
                // Ultra HDR now.
                Pair(StreamConfig.SINGLE_STREAM, setOf(ImageOutputFormat.JPEG)),
                Pair(StreamConfig.MULTI_STREAM, supportedImageFormats)
            ),
            supportedVideoQualitiesMap = supportedVideoQualitiesMap,
            supportedIlluminants = supportedIlluminants,
            supportedFlashModes = supportedFlashModes,
            supportedZoomRange = supportedZoomRange,
            unsupportedStabilizationFpsMap = unsupportedStabilizationFpsMap
        )
    }

    override suspend fun runCamera() = coroutineScope {
        Log.d(TAG, "runCamera")

        val transientSettings = MutableStateFlow<TransientSessionSettings?>(null)
        currentSettings
            .filterNotNull()
            .map { currentCameraSettings ->
                transientSettings.value = TransientSessionSettings(
                    isAudioEnabled = currentCameraSettings.audioEnabled,
                    deviceRotation = currentCameraSettings.deviceRotation,
                    flashMode = currentCameraSettings.flashMode,
                    primaryLensFacing = currentCameraSettings.cameraLensFacing,
                    zoomRatios = currentCameraSettings.defaultZoomRatios
                )

                when (currentCameraSettings.concurrentCameraMode) {
                    ConcurrentCameraMode.OFF -> {
                        val cameraConstraints = checkNotNull(
                            systemConstraints.forCurrentLens(currentCameraSettings)
                        ) {
                            "Could not retrieve constraints for ${currentCameraSettings.cameraLensFacing}"
                        }

                        val resolvedStabilizationMode = resolveStabilizationMode(
                            requestedStabilizationMode = currentCameraSettings.stabilizationMode,
                            targetFrameRate = currentCameraSettings.targetFrameRate,
                            cameraConstraints = cameraConstraints,
                            concurrentCameraMode = currentCameraSettings.concurrentCameraMode
                        )

                        PerpetualSessionSettings.SingleCamera(
                            aspectRatio = currentCameraSettings.aspectRatio,
                            captureMode = currentCameraSettings.captureMode,
                            streamConfig = currentCameraSettings.streamConfig,
                            targetFrameRate = currentCameraSettings.targetFrameRate,
                            stabilizationMode = resolvedStabilizationMode,
                            dynamicRange = currentCameraSettings.dynamicRange,
                            videoQuality = currentCameraSettings.videoQuality,
                            imageFormat = currentCameraSettings.imageFormat
                        )
                    }

                    ConcurrentCameraMode.DUAL -> {
                        val primaryFacing = currentCameraSettings.cameraLensFacing
                        val secondaryFacing = primaryFacing.flip()
                        cameraProvider.availableConcurrentCameraInfos.firstNotNullOf {
                            var primaryCameraInfo: CameraInfo? = null
                            var secondaryCameraInfo: CameraInfo? = null
                            it.forEach { cameraInfo ->
                                if (cameraInfo.appLensFacing == primaryFacing) {
                                    primaryCameraInfo = cameraInfo
                                } else if (cameraInfo.appLensFacing == secondaryFacing) {
                                    secondaryCameraInfo = cameraInfo
                                }
                            }

                            primaryCameraInfo?.let { nonNullPrimary ->
                                secondaryCameraInfo?.let { nonNullSecondary ->
                                    PerpetualSessionSettings.ConcurrentCamera(
                                        primaryCameraInfo = nonNullPrimary,
                                        secondaryCameraInfo = nonNullSecondary,
                                        aspectRatio = currentCameraSettings.aspectRatio
                                    )
                                }
                            }
                        }
                    }
                }
            }.distinctUntilChanged()
            .collectLatest { sessionSettings ->
                coroutineScope {
                    with(
                        CameraSessionContext(
                            context = application,
                            cameraProvider = cameraProvider,
                            backgroundDispatcher = defaultDispatcher,
                            screenFlashEvents = screenFlashEvents,
                            focusMeteringEvents = focusMeteringEvents,
                            videoCaptureControlEvents = videoCaptureControlEvents,
                            currentCameraState = currentCameraState,
                            surfaceRequests = _surfaceRequest,
                            transientSettings = transientSettings
                        )
                    ) {
                        try {
                            when (sessionSettings) {
                                is PerpetualSessionSettings.SingleCamera -> runSingleCameraSession(
                                    sessionSettings,
                                    onFeaturesSelected = { features, cameraInfo, sessionConfig ->
                                        val unsupportedFeatures = getUnsupportedFeatures(
                                            features,
                                            cameraInfo,
                                            sessionConfig
                                        )

                                        disableUnsupportedFeatures(unsupportedFeatures, cameraInfo)
                                    },
                                    onImageCaptureCreated = { imageCapture ->
                                        imageCaptureUseCase = imageCapture
                                    }
                                )

                                is PerpetualSessionSettings.ConcurrentCamera ->
                                    runConcurrentCameraSession(
                                        sessionSettings
                                    )
                            }
                        } finally {
                            // TODO(tm): This shouldn't be necessary. Cancellation of the
                            //  coroutineScope by collectLatest should cause this to
                            //  occur naturally.
                            cameraProvider.unbindAll()
                        }
                    }
                }
            }
    }

    private fun getUnsupportedFeatures(
        currentFeatures: Set<GroupableFeature>,
        cameraInfo: CameraInfo,
        sessionConfig: SessionConfig
    ): Set<GroupableFeature> {
        val unsupportedFeatures = mutableSetOf<GroupableFeature>()

        val appFeatureOptions =
            setOf(HDR_HLG10, GroupableFeature.FPS_60, PREVIEW_STABILIZATION, IMAGE_ULTRA_HDR)

        appFeatureOptions.forEach { featureOption ->
            if (currentFeatures.contains(featureOption)) return@forEach
            if (
                !cameraInfo.isFeatureGroupSupported(
                    sessionConfig.withRequiredFeature(featureOption)
                )
            ) {
                unsupportedFeatures.add(featureOption)
            }
        }
        return unsupportedFeatures
    }

    private fun disableUnsupportedFeatures(
        unsupportedFeatures: Set<GroupableFeature>,
        cameraInfo: CameraInfo
    ) {
        val lensFacing = if (cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK) {
            LensFacing.BACK
        } else {
            LensFacing.FRONT
        }

        val cameraConstraints = createCameraConstraints(cameraInfo, lensFacing)

        val supportedDynamicRanges = cameraConstraints.supportedDynamicRanges.filterNot {
            it == DynamicRange.HLG10 && unsupportedFeatures.contains(HDR_HLG10)
        }

        val supportedFrameRates = cameraConstraints.supportedFixedFrameRates.filterNot {
            it == FPS_60 && unsupportedFeatures.contains(GroupableFeature.FPS_60)
        }

        val supportedStabilizationModes = cameraConstraints.supportedStabilizationModes.filterNot {
            (it == StabilizationMode.ON && unsupportedFeatures.contains(PREVIEW_STABILIZATION)) ||
                // TODO: Handle auto case during resolve instead of here
                (
                    it == StabilizationMode.AUTO && unsupportedFeatures.contains(
                        PREVIEW_STABILIZATION
                    )
                    )
        }

        val supportedImageFormatsMap =
            cameraConstraints.supportedImageFormatsMap.toMutableMap().apply {
                entries.forEach { entry ->
                    this[entry.key] = entry.value.filterNot {
                        it == ImageOutputFormat.JPEG_ULTRA_HDR && unsupportedFeatures.contains(
                            IMAGE_ULTRA_HDR
                        )
                    }.toSet()
                }
            }

        val unsupportedStabilizationFpsMap =
            cameraConstraints.unsupportedStabilizationFpsMap.toMutableMap().apply {
                val unsupportedFrameRates = this[StabilizationMode.ON]
                if (unsupportedFrameRates != null) {
                    this[StabilizationMode.ON] = unsupportedFrameRates.filterNot {
                        it == FPS_60 && !unsupportedFeatures.contains(GroupableFeature.FPS_60)
                    }.toSet()
                }
            }

        val updatedPerLensConstraints = systemConstraints.perLensConstraints.toMutableMap()

        updatedPerLensConstraints[lensFacing] = cameraConstraints.copy(
            supportedDynamicRanges = supportedDynamicRanges.toSet(),
            supportedFixedFrameRates = supportedFrameRates.toSet(),
            supportedStabilizationModes = supportedStabilizationModes.toSet(),
            supportedImageFormatsMap = supportedImageFormatsMap,
            unsupportedStabilizationFpsMap = unsupportedStabilizationFpsMap
        )

        systemConstraints = systemConstraints.copy(perLensConstraints = updatedPerLensConstraints)

        constraintsRepository.updateSystemConstraints(systemConstraints)
    }

    private fun SessionConfig.withRequiredFeature(feature: GroupableFeature) = SessionConfig(
        useCases =
        useCases.filterIsInstance<Preview>() +
            if (
                (requiredFeatureGroup + feature).contains(
                    IMAGE_ULTRA_HDR
                )
            ) {
                listOf(ImageCapture.Builder().build())
            } else {
                emptyList()
            },
        viewPort = viewPort,
        effects = effects,
        frameRateRange = frameRateRange,
        requiredFeatureGroup = requiredFeatureGroup + feature
    )

    private fun resolveStabilizationMode(
        requestedStabilizationMode: StabilizationMode,
        targetFrameRate: Int,
        cameraConstraints: CameraConstraints,
        concurrentCameraMode: ConcurrentCameraMode
    ): StabilizationMode = if (concurrentCameraMode == ConcurrentCameraMode.DUAL) {
        StabilizationMode.OFF
    } else {
        with(cameraConstraints) {
            // Convert AUTO stabilization mode to the first supported stabilization mode
            val stabilizationMode = if (requestedStabilizationMode == StabilizationMode.AUTO) {
                // Choose between ON, OPTICAL, or OFF, depending on support, in that order
                sequenceOf(StabilizationMode.ON, StabilizationMode.OPTICAL, StabilizationMode.OFF)
                    .first {
                        it in supportedStabilizationModes &&
                            targetFrameRate !in it.unsupportedFpsSet
                    }
            } else {
                requestedStabilizationMode
            }

            // Check that the stabilization mode can be supported, otherwise return OFF
            if (stabilizationMode in supportedStabilizationModes &&
                targetFrameRate !in stabilizationMode.unsupportedFpsSet
            ) {
                stabilizationMode
            } else {
                StabilizationMode.OFF
            }
        }
    }

    override suspend fun takePicture(onCaptureStarted: (() -> Unit)) {
        if (imageCaptureUseCase == null) {
            throw RuntimeException("Attempted take picture with null imageCapture use case")
        }
        try {
            val imageProxy = imageCaptureUseCase!!.takePicture(onCaptureStarted)
            Log.d(TAG, "onCaptureSuccess")
            imageProxy.close()
        } catch (exception: Exception) {
            Log.d(TAG, "takePicture onError: $exception")
            throw exception
        }
    }

    // TODO(b/319733374): Return bitmap for external mediastore capture without URI
    override suspend fun takePicture(
        onCaptureStarted: (() -> Unit),
        contentResolver: ContentResolver,
        imageCaptureUri: Uri?,
        ignoreUri: Boolean
    ): ImageCapture.OutputFileResults {
        if (imageCaptureUseCase == null) {
            throw RuntimeException("Attempted take picture with null imageCapture use case")
        }
        val eligibleContentValues = getEligibleContentValues()
        val outputFileOptions: OutputFileOptions
        if (ignoreUri) {
            val formatter = SimpleDateFormat(
                "yyyy-MM-dd-HH-mm-ss-SSS",
                Locale.US
            )
            val filename = "JCA-${formatter.format(Calendar.getInstance().time)}.jpg"
            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            outputFileOptions = OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()
        } else if (imageCaptureUri == null) {
            val e = RuntimeException("Null Uri is provided.")
            Log.d(TAG, "takePicture onError: $e")
            throw e
        } else {
            try {
                val outputStream = contentResolver.openOutputStream(imageCaptureUri)
                if (outputStream != null) {
                    outputFileOptions =
                        OutputFileOptions.Builder(
                            contentResolver.openOutputStream(imageCaptureUri)!!
                        ).build()
                } else {
                    val e = RuntimeException("Provider recently crashed.")
                    Log.d(TAG, "takePicture onError: $e")
                    throw e
                }
            } catch (e: FileNotFoundException) {
                Log.d(TAG, "takePicture onError: $e")
                throw e
            }
        }
        try {
            val outputFileResults = imageCaptureUseCase!!.takePicture(
                outputFileOptions,
                onCaptureStarted
            )
            val relativePath =
                eligibleContentValues.getAsString(MediaStore.Images.Media.RELATIVE_PATH)
            val displayName = eligibleContentValues.getAsString(
                MediaStore.Images.Media.DISPLAY_NAME
            )
            Log.d(TAG, "Saved image to $relativePath/$displayName")
            return outputFileResults
        } catch (exception: ImageCaptureException) {
            Log.d(TAG, "takePicture onError: $exception")
            throw exception
        }
    }

    private fun getEligibleContentValues(): ContentValues {
        val eligibleContentValues = ContentValues()
        eligibleContentValues.put(
            MediaStore.Images.Media.DISPLAY_NAME,
            Calendar.getInstance().time.toString()
        )
        eligibleContentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        eligibleContentValues.put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES
        )
        return eligibleContentValues
    }

    override suspend fun startVideoRecording(
        videoCaptureUri: Uri?,
        shouldUseUri: Boolean,
        onVideoRecord: (CameraUseCase.OnVideoRecordEvent) -> Unit
    ) {
        val initialRecordSettings = currentSettings.value
        if (shouldUseUri && videoCaptureUri == null) {
            val e = RuntimeException("Null Uri is provided.")
            Log.d(TAG, "takePicture onError: $e")
            throw e
        }
        videoCaptureControlEvents.send(
            VideoCaptureControlEvent.StartRecordingEvent(
                videoCaptureUri,
                shouldUseUri,
                currentSettings.value?.maxVideoDurationMillis
                    ?: UNLIMITED_VIDEO_DURATION,
                onVideoRecord = onVideoRecord,

                onRestoreSettings = {
                    // restore settings to be called after video recording completes.
                    // this resets certain settings to their values pre-recording
                    initialRecordSettings?.let {
                        currentSettings.update { old ->
                            old?.copy(
                                cameraLensFacing = initialRecordSettings.cameraLensFacing,
                                defaultZoomRatios = initialRecordSettings.defaultZoomRatios
                            )
                        }
                    }
                }
            )
        )
    }

    override suspend fun pauseVideoRecording() {
        videoCaptureControlEvents.send(VideoCaptureControlEvent.PauseRecordingEvent)
    }

    override suspend fun resumeVideoRecording() {
        videoCaptureControlEvents.send(VideoCaptureControlEvent.ResumeRecordingEvent)
    }

    override suspend fun stopVideoRecording() {
        videoCaptureControlEvents.send(VideoCaptureControlEvent.StopRecordingEvent)
    }

    override fun changeZoomRatio(newZoomState: CameraZoomRatio) {
        currentSettings.update { old ->
            old?.tryApplyNewZoomRatio(newZoomState) ?: old
        }
    }

    // Sets the camera to the designated lensFacing direction
    override suspend fun setLensFacing(lensFacing: LensFacing) {
        currentSettings.update { old ->
            if (systemConstraints.availableLenses.contains(lensFacing)) {
                old?.copy(cameraLensFacing = lensFacing)
                    ?.tryApplyDynamicRangeConstraints()
                    ?.tryApplyImageFormatConstraints()
                    ?.tryApplyFlashModeConstraints()
                    ?.tryApplyCaptureModeConstraints()
            } else {
                old
            }
        }
    }

    /**
     * Applies an appropriate Capture Mode for given settings, if necessary
     *
     * Should be applied whenever
     * [tryApplyImageFormatConstraints],
     * [tryApplyDynamicRangeConstraints],
     * or [tryApplyConcurrentCameraModeConstraints] would be called
     *
     * @param defaultCaptureMode if multiple capture modes are supported by the device, this capture
     * mode will be applied. If left null, it will not change the current capture mode.
     */
    private fun CameraAppSettings.tryApplyCaptureModeConstraints(
        defaultCaptureMode: CaptureMode? = null
    ): CameraAppSettings {
        Log.d(TAG, "applying capture mode constraints")
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            val newCaptureMode =
                // concurrent currently only supports VIDEO_ONLY
                if (concurrentCameraMode == ConcurrentCameraMode.DUAL) {
                    CaptureMode.VIDEO_ONLY
                }

                // if hdr is enabled...
                else if (imageFormat == ImageOutputFormat.JPEG_ULTRA_HDR ||
                    dynamicRange == DynamicRange.HLG10
                ) {
                    // if both hdr video and image capture are selected and supported, default to
                    // VIDEO_ONLY
                    if (constraints.supportedDynamicRanges.contains(DynamicRange.HLG10) &&
                        dynamicRange == DynamicRange.HLG10 &&
                        constraints.supportedImageFormatsMap[streamConfig]
                            ?.contains(ImageOutputFormat.JPEG_ULTRA_HDR) == true &&
                        imageFormat == ImageOutputFormat.JPEG_ULTRA_HDR
                    ) {
                        if (captureMode == CaptureMode.STANDARD) {
                            CaptureMode.VIDEO_ONLY
                        } else {
                            return this
                        }
                    }
                    // return appropriate capture mode if only one is supported and selected
                    else if (imageFormat == ImageOutputFormat.JPEG_ULTRA_HDR &&
                        constraints.supportedImageFormatsMap[streamConfig]
                            ?.contains(ImageOutputFormat.JPEG_ULTRA_HDR) == true
                    ) {
                        CaptureMode.IMAGE_ONLY
                    } else if (constraints.supportedDynamicRanges.contains(DynamicRange.HLG10) &&
                        dynamicRange == DynamicRange.HLG10
                    ) {
                        CaptureMode.VIDEO_ONLY
                    } else {
                        return this
                    }
                } else {
                    defaultCaptureMode ?: return this
                }

            Log.d(TAG, "new capture mode $newCaptureMode")
            return this@tryApplyCaptureModeConstraints.copy(
                captureMode = newCaptureMode
            )
        }
            ?: return this
    }

    private fun CameraAppSettings.tryApplyNewZoomRatio(
        newZoomState: CameraZoomRatio
    ): CameraAppSettings {
        val lensFacing = when (newZoomState.changeType.lensToZoom) {
            LensToZoom.PRIMARY -> cameraLensFacing
            LensToZoom.SECONDARY -> {
                val newLens = cameraLensFacing.flip()
                check(systemConstraints.perLensConstraints[newLens] != null) {
                    "Device does not have a secondary camera"
                }
                newLens
            }
        }
        return systemConstraints.perLensConstraints[lensFacing]?.let { constraints ->
            val newZoomRatio = constraints.supportedZoomRange?.let { zoomRatioRange ->
                when (val change = newZoomState.changeType) {
                    is ZoomChange.Absolute -> change.value
                    is ZoomChange.Scale -> (
                        this.defaultZoomRatios
                            [lensFacing]
                            ?: 1.0f
                        ) *
                        change.value

                    is ZoomChange.Increment -> {
                        (this.defaultZoomRatios[lensFacing] ?: 1.0f) + change.value
                    }
                }.coerceIn(zoomRatioRange.lower, zoomRatioRange.upper)
            } ?: 1f
            this@tryApplyNewZoomRatio
                .copy(
                    defaultZoomRatios = this.defaultZoomRatios.toMutableMap().apply {
                        put(lensFacing, newZoomRatio)
                    }
                )
        } ?: this
    }

    private fun CameraAppSettings.tryApplyDynamicRangeConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedDynamicRanges) {
                val newDynamicRange = if (contains(dynamicRange)) {
                    dynamicRange
                } else {
                    DynamicRange.SDR
                }

                this@tryApplyDynamicRangeConstraints.copy(
                    dynamicRange = newDynamicRange
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyAspectRatioForExternalCapture(
        captureMode: CaptureMode
    ): CameraAppSettings = when (captureMode) {
        CaptureMode.STANDARD -> this
        CaptureMode.IMAGE_ONLY ->
            this.copy(aspectRatio = AspectRatio.THREE_FOUR)
        CaptureMode.VIDEO_ONLY ->
            this.copy(aspectRatio = AspectRatio.NINE_SIXTEEN)
    }

    private fun CameraAppSettings.tryApplyImageFormatConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedImageFormatsMap[streamConfig]) {
                val newImageFormat = if (this != null && contains(imageFormat)) {
                    imageFormat
                } else {
                    ImageOutputFormat.JPEG
                }

                this@tryApplyImageFormatConstraints.copy(
                    imageFormat = newImageFormat
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyFrameRateConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedFixedFrameRates) {
                val newTargetFrameRate = if (contains(targetFrameRate)) {
                    targetFrameRate
                } else {
                    TARGET_FPS_AUTO
                }

                this@tryApplyFrameRateConstraints.copy(
                    targetFrameRate = newTargetFrameRate
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyStabilizationConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints) {
                val newStabilizationMode = if (stabilizationMode != StabilizationMode.AUTO &&
                    stabilizationMode in constraints.supportedStabilizationModes &&
                    targetFrameRate !in stabilizationMode.unsupportedFpsSet
                ) {
                    stabilizationMode
                } else {
                    StabilizationMode.AUTO
                }

                this@tryApplyStabilizationConstraints.copy(
                    stabilizationMode = newStabilizationMode
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyConcurrentCameraModeConstraints(): CameraAppSettings =
        when (concurrentCameraMode) {
            ConcurrentCameraMode.OFF -> this
            else ->
                if (systemConstraints.concurrentCamerasSupported &&
                    dynamicRange == DynamicRange.SDR &&
                    streamConfig == StreamConfig.MULTI_STREAM
                ) {
                    copy(
                        targetFrameRate = TARGET_FPS_AUTO
                    )
                } else {
                    copy(concurrentCameraMode = ConcurrentCameraMode.OFF)
                }
        }

    private fun CameraAppSettings.tryApplyVideoQualityConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedVideoQualitiesMap) {
                val newVideoQuality = get(dynamicRange).let {
                    if (it == null) {
                        VideoQuality.UNSPECIFIED
                    } else if (it.contains(videoQuality)) {
                        videoQuality
                    } else {
                        VideoQuality.UNSPECIFIED
                    }
                }

                this@tryApplyVideoQualityConstraints.copy(
                    videoQuality = newVideoQuality
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyFlashModeConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedFlashModes) {
                val newFlashMode = if (contains(flashMode)) {
                    flashMode
                } else {
                    FlashMode.OFF
                }

                this@tryApplyFlashModeConstraints.copy(
                    flashMode = newFlashMode
                )
            }
        } ?: this

    override suspend fun tapToFocus(x: Float, y: Float) {
        focusMeteringEvents.send(CameraEvent.FocusMeteringEvent(x, y))
    }

    override fun getScreenFlashEvents() = screenFlashEvents
    override fun getCurrentSettings() = currentSettings.asStateFlow()

    override fun setFlashMode(flashMode: FlashMode) {
        currentSettings.update { old ->
            old?.copy(flashMode = flashMode)
        }
    }

    override fun isScreenFlashEnabled() =
        imageCaptureUseCase?.flashMode == ImageCapture.FLASH_MODE_SCREEN &&
            imageCaptureUseCase?.screenFlash != null

    override suspend fun setAspectRatio(aspectRatio: AspectRatio) {
        currentSettings.update { old ->
            old?.copy(aspectRatio = aspectRatio)
        }
    }

    override suspend fun setVideoQuality(videoQuality: VideoQuality) {
        currentSettings.update { old ->
            old?.copy(videoQuality = videoQuality)
                ?.tryApplyVideoQualityConstraints()
        }
    }

    override suspend fun setStreamConfig(streamConfig: StreamConfig) {
        currentSettings.update { old ->
            old?.copy(streamConfig = streamConfig)
                ?.tryApplyImageFormatConstraints()
                ?.tryApplyConcurrentCameraModeConstraints()
                ?.tryApplyCaptureModeConstraints()
                ?.tryApplyVideoQualityConstraints()
        }
    }

    override fun setDynamicRange(dynamicRange: DynamicRange) {
        currentSettings.update { old ->
            old?.copy(dynamicRange = dynamicRange)
                ?.tryApplyDynamicRangeConstraints()
                ?.tryApplyConcurrentCameraModeConstraints()
                ?.tryApplyCaptureModeConstraints(CaptureMode.STANDARD)
        }
    }

    override fun setDeviceRotation(deviceRotation: DeviceRotation) {
        currentSettings.update { old ->
            old?.copy(deviceRotation = deviceRotation)
        }
    }

    override suspend fun setConcurrentCameraMode(concurrentCameraMode: ConcurrentCameraMode) {
        currentSettings.update { old ->
            old?.copy(concurrentCameraMode = concurrentCameraMode)
                ?.tryApplyConcurrentCameraModeConstraints()
                ?.tryApplyCaptureModeConstraints(CaptureMode.STANDARD)
        }
    }

    override fun setImageFormat(imageFormat: ImageOutputFormat) {
        currentSettings.update { old ->
            old?.copy(imageFormat = imageFormat)
                ?.tryApplyImageFormatConstraints()
                ?.tryApplyCaptureModeConstraints(CaptureMode.STANDARD)
        }
    }

    override suspend fun setMaxVideoDuration(durationInMillis: Long) {
        currentSettings.update { old ->
            old?.copy(
                maxVideoDurationMillis = durationInMillis
            )
        }
    }

    override fun setStabilizationMode(stabilizationMode: StabilizationMode) {
        currentSettings.update { old ->
            old?.copy(stabilizationMode = stabilizationMode)
        }
    }

    override fun setTargetFrameRate(targetFrameRate: Int) {
        currentSettings.update { old ->
            old?.copy(targetFrameRate = targetFrameRate)?.tryApplyFrameRateConstraints()
                ?.tryApplyConcurrentCameraModeConstraints()
        }
    }

    override suspend fun setAudioEnabled(isAudioEnabled: Boolean) {
        currentSettings.update { old ->
            old?.copy(audioEnabled = isAudioEnabled)
        }
    }

    override suspend fun setCaptureMode(captureMode: CaptureMode) {
        currentSettings.update { old ->
            old?.copy(captureMode = captureMode)
        }
    }

    companion object {
        private val FIXED_FRAME_RATES = setOf(TARGET_FPS_15, TARGET_FPS_30, TARGET_FPS_60)
    }
}
