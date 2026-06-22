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
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.multiset.sdk.LocalizationMode
import com.multiset.sdk.LocalizationResult
import com.multiset.sdk.MultiSetCallback
import com.multiset.sdk.MultiSetConfig
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.ObjectTrackingResult
import com.multiset.sdk.TrackingState
import com.multiset.sdk.android.databinding.ActivityMainBinding
import com.multiset.sdk.android.ui.MultiSetLocalizationActivity
import com.multiset.sdk.android.ui.ObjectTrackingActivity

/**
 * Demo app showing how to integrate MultiSet SDK.
 *
 * This demonstrates:
 * 1. Initializing the SDK with credentials
 * 2. Handling authentication
 * 3. Launching single-frame and multi-frame AR localization
 * 4. Launching object tracking
 */
class MainActivity :
    AppCompatActivity(),
    MultiSetCallback {
    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var pendingLocalizationType: LocalizationMode? = null
    private var pendingObjectTracking = false
    private var selectedMode: LocalizationMode = LocalizationMode.MULTI_FRAME

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

        // Apply any saved configuration before the AR activities read the config objects.
        ConfigStore.load(this)

        setupUI()
        displayMapCode()
        displayObjectCodes()
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
            return
        }

        if (mapCode.isEmpty() && mapSetCode.isEmpty()) {
            val objectCodes = BuildConfig.MULTISET_OBJECT_CODES.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (objectCodes.isEmpty()) {
                showConfigurationAlert()
                return
            }
        }

        val configBuilder = MultiSetConfig.Builder(clientId, clientSecret)

        if (mapCode.isNotEmpty()) {
            configBuilder.mapCode(mapCode)
        } else if (mapSetCode.isNotEmpty()) {
            configBuilder.mapSetCode(mapSetCode)
        }

        val config =
            configBuilder
                .enableMeshVisualization(true)
                .backgroundLocalization(true)
                .build()

        MultiSetSDK.initialize(this, config, this)
    }

    private fun displayMapCode() {
        val mapCode = BuildConfig.MULTISET_MAP_CODE
        val mapSetCode = BuildConfig.MULTISET_MAP_SET_CODE

        when {
            mapCode.isEmpty() && mapSetCode.isEmpty() -> {
                binding.mapCodeContainer.visibility = View.GONE
                binding.noMapText.visibility = View.VISIBLE
            }
            mapCode.isNotEmpty() -> {
                binding.mapCodeContainer.visibility = View.VISIBLE
                binding.noMapText.visibility = View.GONE
                binding.mapCodeText.text = mapCode
            }
            else -> {
                binding.mapCodeContainer.visibility = View.VISIBLE
                binding.noMapText.visibility = View.GONE
                binding.mapCodeText.text = mapSetCode
            }
        }
    }

    private fun displayObjectCodes() {
        val objectCodes = BuildConfig.MULTISET_OBJECT_CODES.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (objectCodes.isEmpty()) {
            binding.noObjectCodesText.visibility = View.VISIBLE
            binding.objectCodesContainer.visibility = View.GONE
            binding.objectCountBadge.visibility = View.GONE
        } else {
            binding.noObjectCodesText.visibility = View.GONE
            binding.objectCodesContainer.visibility = View.VISIBLE
            binding.objectCountBadge.visibility = View.VISIBLE
            binding.objectCountBadge.text =
                "${objectCodes.size} object${if (objectCodes.size == 1) "" else "s"}"

            binding.objectCodesContainer.removeAllViews()
            objectCodes.forEachIndexed { index, code ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index > 0) topMargin = dpToPx(6)
                    }
                }

                val icon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_cube)
                    imageTintList = ColorStateList.valueOf(0xB300BCD4.toInt())
                    layoutParams = LinearLayout.LayoutParams(dpToPx(14), dpToPx(14))
                }

                val text = TextView(this).apply {
                    text = code
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dpToPx(6)
                    }
                }

                row.addView(icon)
                row.addView(text)
                binding.objectCodesContainer.addView(row)
            }
        }
    }

    private fun showConfigurationAlert() {
        AlertDialog
            .Builder(this)
            .setTitle("Configuration Required")
            .setMessage("No map codes or object codes are configured. Please update multiset.properties.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }.setCancelable(false)
            .show()
    }

    private fun setupUI() {
        binding.localizationButton.isEnabled = false
        binding.objectTrackingButton.isEnabled = false
        binding.authButton.isEnabled = false

        // Default mode = Multi Frame (matches iOS default)
        binding.modeToggleGroup.check(R.id.multiFrameButton)

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedMode = when (checkedId) {
                    R.id.singleFrameButton -> LocalizationMode.SINGLE_FRAME
                    else -> LocalizationMode.MULTI_FRAME
                }
            }
        }

        binding.settingsButton.setOnClickListener {
            SettingsDialogFragment
                .newInstance(selectedMode == LocalizationMode.MULTI_FRAME)
                .show(supportFragmentManager, SettingsDialogFragment.TAG)
        }

        binding.authButton.setOnClickListener {
            initializeSDK()
        }

        binding.localizationButton.setOnClickListener {
            val mapCode = BuildConfig.MULTISET_MAP_CODE
            val mapSetCode = BuildConfig.MULTISET_MAP_SET_CODE
            if (mapCode.isEmpty() && mapSetCode.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Map Code Required")
                    .setMessage("Please configure a mapCode or mapSetCode in multiset.properties to start localization.")
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
                return@setOnClickListener
            }
            pendingLocalizationType = selectedMode
            pendingObjectTracking = false
            if (checkCameraPermission()) {
                checkARCoreAndProceed()
            }
        }

        binding.objectTrackingButton.setOnClickListener {
            pendingObjectTracking = true
            pendingLocalizationType = null
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
        if (pendingObjectTracking) {
            val objectCodes = BuildConfig.MULTISET_OBJECT_CODES.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toTypedArray()

            if (objectCodes.isEmpty()) {
                showToast("No object codes configured. Set MULTISET_OBJECT_CODES in multiset.properties")
                return
            }

            ObjectTrackingConfig.objectCodes = objectCodes
            ObjectTrackingConfig.validate()

            val intent = Intent(this, ObjectTrackingActivity::class.java)
            intent.putExtra(ObjectTrackingActivity.EXTRA_OBJECT_CODES, objectCodes)
            startActivity(intent)
        } else {
            val intent = Intent(this, MultiSetLocalizationActivity::class.java)
            intent.putExtra(
                MultiSetLocalizationActivity.EXTRA_LOCALIZATION_MODE,
                (pendingLocalizationType ?: LocalizationMode.MULTI_FRAME).name
            )
            startActivity(intent)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // ============================================================
    // MultiSetCallback Implementation
    // ============================================================

    override fun onSDKReady() {
        runOnUiThread {
            binding.authButton.text = getString(R.string.authenticating)
        }
    }

    override fun onAuthenticationSuccess() {
        runOnUiThread {
            binding.authButton.text = getString(R.string.authenticated)
            binding.authButton.isEnabled = false
            binding.authButton.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.success))
            binding.authButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_check)
            binding.localizationButton.isEnabled = true
            binding.objectTrackingButton.isEnabled = true
            showToast("Authentication successful")
        }
    }

    override fun onAuthenticationFailure(error: String) {
        runOnUiThread {
            binding.authButton.isEnabled = true
            binding.authButton.text = getString(R.string.authenticate)
            showToast("Authentication failed: $error")
        }
    }

    override fun onLocalizationSuccess(result: LocalizationResult) {
        Log.d(TAG, "Localization success - mapCode: ${result.mapCode}, " +
                "mapCodes: ${result.mapCodes}, " +
                "position: [${result.position.joinToString()}], " +
                "rotation: [${result.rotation.joinToString()}], " +
                "confidence: ${result.confidence}")
    }

    override fun onLocalizationFailure(error: String) {
        // Handle localization failure - AR activity handles this internally
    }

    override fun onTrackingStateChanged(state: TrackingState) {
        // Handle tracking state changes - AR activity handles this internally
    }

    override fun onObjectTrackingSuccess(result: ObjectTrackingResult) {
        Log.d(TAG, "Object tracking success - objectCode: ${result.objectCode}, " +
                "objectCodes: ${result.objectCodes}, " +
                "position: [${result.position.joinToString()}], " +
                "rotation: [${result.rotation.joinToString()}], " +
                "confidence: ${result.confidence}")
    }

    override fun onObjectTrackingFailure(error: String) {
        Log.d(TAG, "Object tracking failure: $error")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't release SDK here as we want it to persist across activity launches
    }
}
