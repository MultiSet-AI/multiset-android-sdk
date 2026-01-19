/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.sdk.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk

import com.multiset.sdk.android.databinding.ActivityMainBinding
import com.multiset.sdk.android.ui.MultiFrameARActivity
import com.multiset.sdk.android.ui.SingleFrameARActivity
import com.multiset.sdk.LocalizationMode
import com.multiset.sdk.LocalizationResult
import com.multiset.sdk.MultiSetCallback
import com.multiset.sdk.MultiSetConfig
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.TrackingState
import com.multiset.sdk.android.utils.NetworkUtils
import kotlin.jvm.java


/**
 * Demo app showing how to integrate MultiSet SDK.
 *
 * This demonstrates:
 * 1. Initializing the SDK with credentials
 * 2. Handling authentication
 * 3. Launching single-frame and multi-frame AR localization
 */
class MainActivity :
    AppCompatActivity(),
    MultiSetCallback {
    private lateinit var binding: ActivityMainBinding
    private var pendingLocalizationType: LocalizationMode? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                checkARCoreAndProceed()
            } else {
                showToast("Camera permission is required for localization")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        displayMapCode()
        initializeSDK()

        binding.instructionsText.setOnClickListener {
            val url = "https://developer.multiset.ai/credentials"
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = android.net.Uri.parse(url)
            startActivity(intent)
        }
    }

    private fun initializeSDK() {
        val clientId = BuildConfig.MULTISET_CLIENT_ID
        val clientSecret = BuildConfig.MULTISET_CLIENT_SECRET
        val mapCode = BuildConfig.MULTISET_MAP_CODE
        val mapSetCode = BuildConfig.MULTISET_MAP_SET_CODE

        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            binding.statusText.text = "Please configure credentials in build.gradle"
            return
        }

        if (mapCode.isEmpty() && mapSetCode.isEmpty()) {
            showConfigurationAlert()
            return
        }

        binding.statusText.text = "Initializing SDK..."

        // Build SDK configuration
        val configBuilder = MultiSetConfig.Builder(clientId, clientSecret)

        if (mapCode.isNotEmpty()) {
            configBuilder.mapCode(mapCode)
        } else {
            configBuilder.mapSetCode(mapSetCode)
        }

        val config =
            configBuilder
                .enableMeshVisualization(true)
                .backgroundLocalization(true)
                .build()

        // Initialize SDK
        MultiSetSDK.initialize(this, config, this)
    }

    private fun displayMapCode() {
        val mapCode = BuildConfig.MULTISET_MAP_CODE
        val mapSetCode = BuildConfig.MULTISET_MAP_SET_CODE

        when {
            mapCode.isEmpty() && mapSetCode.isEmpty() -> {
                binding.mapCodeText.text = "No Map Configured"
            }

            mapCode.isNotEmpty() -> {
                binding.mapCodeText.text = "Map Code: $mapCode"
            }

            else -> {
                binding.mapCodeText.text = "Map Set Code: $mapSetCode"
            }
        }
    }

    private fun showConfigurationAlert() {
        AlertDialog
            .Builder(this)
            .setTitle("Configuration Required")
            .setMessage("Both MAP_CODE and MAP_SET_CODE are empty. Please configure at least one in build.gradle.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }.setCancelable(false)
            .show()
    }

    private fun setupUI() {
        // Initially disable localization buttons
        binding.singleFrameButton.isEnabled = false
        binding.multiFrameButton.isEnabled = false
        binding.authButton.isEnabled = false

        binding.authButton.setOnClickListener {
            // Re-initialize SDK to trigger authentication
            initializeSDK()
        }

        binding.singleFrameButton.setOnClickListener {
            pendingLocalizationType = LocalizationMode.SINGLE_FRAME
            if (checkCameraPermission()) {
                checkARCoreAndProceed()
            }
        }

        binding.multiFrameButton.setOnClickListener {
            pendingLocalizationType = LocalizationMode.MULTI_FRAME
            if (checkCameraPermission()) {
                checkARCoreAndProceed()
            }
        }
    }

    private fun checkCameraPermission(): Boolean =
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            false
        } else {
            true
        }

    private fun checkARCoreAndProceed() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                startARSession()
            }

            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
                -> {
                try {
                    val installStatus = ArCoreApk.getInstance().requestInstall(this, true)
                    if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                        showToast("Please install ARCore and restart the app")
                    }
                } catch (e: Exception) {
                    showToast("ARCore installation failed")
                }
            }

            else -> {
                showToast("ARCore is not supported on this device")
            }
        }
    }

    private fun startARSession() {
        if (!NetworkUtils.isInternetAvailable(this)) {
            showToast("No internet connection. Please check your network and try again.")
            return
        }

        when (pendingLocalizationType) {
            LocalizationMode.SINGLE_FRAME -> {
                val intent = Intent(this, SingleFrameARActivity::class.java)
                startActivity(intent)
            }

            LocalizationMode.MULTI_FRAME, null -> {
                val intent = Intent(this, MultiFrameARActivity::class.java)
                startActivity(intent)
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // MultiSetCallback Implementation
    // ============================================================

    override fun onSDKReady() {
        runOnUiThread {
            binding.statusText.text = "Authenticating..."
        }
    }

    override fun onAuthenticationSuccess() {
        runOnUiThread {
            binding.statusText.text = "Authenticated"
            binding.authButton.text = "Authenticated"
            binding.authButton.isEnabled = false
            binding.singleFrameButton.isEnabled = true
            binding.multiFrameButton.isEnabled = true
            showToast("Authentication successful")
        }
    }

    override fun onAuthenticationFailure(error: String) {
        runOnUiThread {
            binding.statusText.text = "Authentication Failed"
            binding.authButton.isEnabled = true
            binding.authButton.text = getString(R.string.auth)
            showToast("Authentication failed: $error")
        }
    }

    override fun onLocalizationSuccess(result: LocalizationResult) {
        // Handle localization success - AR activity handles this internally
    }

    override fun onLocalizationFailure(error: String) {
        // Handle localization failure - AR activity handles this internally
    }

    override fun onTrackingStateChanged(state: TrackingState) {
        // Handle tracking state changes - AR activity handles this internally
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't release SDK here as we want it to persist across activity launches
    }
}
