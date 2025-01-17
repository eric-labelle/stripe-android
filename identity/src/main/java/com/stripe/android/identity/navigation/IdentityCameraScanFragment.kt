package com.stripe.android.identity.navigation

import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import androidx.annotation.IdRes
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.stripe.android.camera.Camera1Adapter
import com.stripe.android.camera.scanui.CameraView
import com.stripe.android.camera.scanui.util.asRect
import com.stripe.android.core.exception.InvalidResponseException
import com.stripe.android.identity.R
import com.stripe.android.identity.analytics.IdentityAnalyticsRequestFactory.Companion.TYPE_DOCUMENT
import com.stripe.android.identity.analytics.IdentityAnalyticsRequestFactory.Companion.TYPE_SELFIE
import com.stripe.android.identity.ml.FaceDetectorOutput
import com.stripe.android.identity.ml.IDDetectorOutput
import com.stripe.android.identity.navigation.CouldNotCaptureFragment.Companion.ARG_COULD_NOT_CAPTURE_SCAN_TYPE
import com.stripe.android.identity.navigation.CouldNotCaptureFragment.Companion.ARG_REQUIRE_LIVE_CAPTURE
import com.stripe.android.identity.networking.Status
import com.stripe.android.identity.states.IdentityScanState
import com.stripe.android.identity.states.IdentityScanState.Companion.isBack
import com.stripe.android.identity.states.IdentityScanState.Companion.isFront
import com.stripe.android.identity.utils.navigateToDefaultErrorFragment
import com.stripe.android.identity.viewmodel.CameraViewModel
import com.stripe.android.identity.viewmodel.IdentityScanViewModel
import com.stripe.android.identity.viewmodel.IdentityViewModel
import kotlinx.coroutines.launch

/**
 * An abstract [Fragment] class to access camera scanning for Identity.
 *
 * Subclasses are responsible for populating [cameraView] in its [Fragment.onCreateView] method.
 */
internal abstract class IdentityCameraScanFragment(
    private val identityCameraScanViewModelFactory: ViewModelProvider.Factory,
    private val identityViewModelFactory: ViewModelProvider.Factory
) : Fragment() {
    protected val identityScanViewModel: IdentityScanViewModel by viewModels { identityCameraScanViewModelFactory }
    protected val identityViewModel: IdentityViewModel by activityViewModels { identityViewModelFactory }

    @get:IdRes
    protected abstract val fragmentId: Int

    @VisibleForTesting
    internal lateinit var cameraAdapter: Camera1Adapter

    /**
     * [CameraView] to initialize [Camera1Adapter], subclasses needs to set its value in
     * [Fragment.onCreateView].
     */
    protected lateinit var cameraView: CameraView

    /**
     * Called back at end of [onViewCreated] when permission is granted.
     */
    protected abstract fun onCameraReady()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        identityScanViewModel.displayStateChanged.observe(viewLifecycleOwner) { (newState, _) ->
            updateUI(newState)
        }

        identityScanViewModel.interimResults.observe(viewLifecycleOwner) {
            identityViewModel.fpsTracker.trackFrame()
        }

        identityScanViewModel.finalResult.observe(viewLifecycleOwner) { finalResult ->
            lifecycleScope.launch {
                identityViewModel.fpsTracker.reportAndReset(
                    if (finalResult.result is FaceDetectorOutput) TYPE_SELFIE else TYPE_DOCUMENT
                )
            }

            identityViewModel.observeForVerificationPage(
                viewLifecycleOwner,
                onSuccess = { verificationPage ->
                    if (finalResult.identityState is IdentityScanState.Finished) {
                        when (finalResult.result) {
                            is FaceDetectorOutput -> {
                                identityViewModel.updateAnalyticsState { oldState ->
                                    oldState.copy(selfieModelScore = finalResult.result.resultScore)
                                }
                            }
                            is IDDetectorOutput -> {
                                if (finalResult.identityState.type.isFront()) {
                                    identityViewModel.updateAnalyticsState { oldState ->
                                        oldState.copy(docFrontModelScore = finalResult.result.resultScore)
                                    }
                                } else if (finalResult.identityState.type.isBack()) {
                                    identityViewModel.updateAnalyticsState { oldState ->
                                        oldState.copy(docBackModelScore = finalResult.result.resultScore)
                                    }
                                }
                            }
                        }
                        identityViewModel.uploadScanResult(
                            finalResult,
                            verificationPage,
                            identityScanViewModel.targetScanType
                        )
                    } else if (finalResult.identityState is IdentityScanState.TimeOut) {
                        when (finalResult.result) {
                            is FaceDetectorOutput -> {
                                identityViewModel.sendAnalyticsRequest(
                                    identityViewModel.identityAnalyticsRequestFactory.selfieTimeout()
                                )
                            }
                            is IDDetectorOutput -> {
                                identityViewModel.sendAnalyticsRequest(
                                    identityViewModel.identityAnalyticsRequestFactory.documentTimeout(
                                        scanType = finalResult.identityState.type
                                    )
                                )
                            }
                        }
                        findNavController().navigate(
                            R.id.action_global_couldNotCaptureFragment,
                            bundleOf(
                                ARG_COULD_NOT_CAPTURE_SCAN_TYPE to identityScanViewModel.targetScanType
                            ).also {
                                if (identityScanViewModel.targetScanType != IdentityScanState.ScanType.SELFIE) {
                                    it.putBoolean(
                                        ARG_REQUIRE_LIVE_CAPTURE,
                                        verificationPage.documentCapture.requireLiveCapture
                                    )
                                }
                            }
                        )
                    }
                },
                onFailure = {
                    Log.e(TAG, "Fail to observeForVerificationPage: $it")
                    navigateToDefaultErrorFragment(it)
                }
            )
            stopScanning()
        }
        cameraAdapter = createCameraAdapter()

        identityViewModel.pageAndModelFiles.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.SUCCESS -> {
                    requireNotNull(it.data).let { pageAndModelFiles ->
                        identityScanViewModel.initializeScanFlow(
                            pageAndModelFiles.page,
                            idDetectorModelFile = pageAndModelFiles.idDetectorFile,
                            faceDetectorModelFile = pageAndModelFiles.faceDetectorFile
                        )
                        lifecycleScope.launch(identityViewModel.uiContext) {
                            onCameraReady()
                        }
                    }
                }
                Status.LOADING -> {} // no-op
                Status.IDLE -> {} // no-op
                Status.ERROR -> {
                    throw InvalidResponseException(
                        cause = it.throwable,
                        message = it.message
                    )
                }
            }
        }
    }

    protected abstract fun createCameraAdapter(): Camera1Adapter

    /**
     * Called back each time when [CameraViewModel.displayStateChanged] is changed.
     */
    protected abstract fun updateUI(identityScanState: IdentityScanState)

    protected abstract fun resetUI()

    /**
     * Start scanning for the required scan type.
     */
    internal fun startScanning(scanType: IdentityScanState.ScanType) {
        identityViewModel.updateAnalyticsState { oldState ->
            when (scanType) {
                IdentityScanState.ScanType.ID_FRONT -> {
                    oldState.copy(
                        docFrontRetryTimes =
                        oldState.docFrontRetryTimes?.let { it + 1 } ?: 1
                    )
                }
                IdentityScanState.ScanType.ID_BACK -> {
                    oldState.copy(
                        docBackRetryTimes =
                        oldState.docBackRetryTimes?.let { it + 1 } ?: 1
                    )
                }
                IdentityScanState.ScanType.DL_FRONT -> {
                    oldState.copy(
                        docFrontRetryTimes =
                        oldState.docFrontRetryTimes?.let { it + 1 } ?: 1
                    )
                }
                IdentityScanState.ScanType.DL_BACK -> {
                    oldState.copy(
                        docBackRetryTimes =
                        oldState.docBackRetryTimes?.let { it + 1 } ?: 1
                    )
                }
                IdentityScanState.ScanType.PASSPORT -> {
                    oldState.copy(
                        docFrontRetryTimes =
                        oldState.docFrontRetryTimes?.let { it + 1 } ?: 1
                    )
                }
                IdentityScanState.ScanType.SELFIE -> {
                    oldState.copy(
                        selfieRetryTimes =
                        oldState.selfieRetryTimes?.let { it + 1 } ?: 1
                    )
                }
            }
        }
        identityScanViewModel.targetScanType = scanType
        resetUI()
        cameraAdapter.bindToLifecycle(this)
        identityScanViewModel.scanState = null
        identityScanViewModel.scanStatePrevious = null

        identityViewModel.fpsTracker.start()
        identityScanViewModel.identityScanFlow?.startFlow(
            context = requireContext(),
            imageStream = cameraAdapter.getImageStream(),
            viewFinder = cameraView.viewFinderWindowView.asRect(),
            lifecycleOwner = viewLifecycleOwner,
            coroutineScope = lifecycleScope,
            parameters = scanType
        )
    }

    /**
     * Stop scanning, may start again later.
     */
    protected fun stopScanning() {
        identityScanViewModel.identityScanFlow?.resetFlow()
        cameraAdapter.unbindFromLifecycle(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Cancelling IdentityScanFlow")
        identityScanViewModel.identityScanFlow?.cancelFlow()
    }

    internal companion object {
        private val TAG: String = IdentityCameraScanFragment::class.java.simpleName
        val MINIMUM_RESOLUTION = Size(1440, 1080)
    }
}
