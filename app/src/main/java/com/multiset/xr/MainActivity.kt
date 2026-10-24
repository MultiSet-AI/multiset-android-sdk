/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr

import android.content.Intent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.multiset.xr.config.ConfigStore
import com.multiset.xr.ui.padForSystemBars
import com.multiset.xr.databinding.ActivityMainBinding
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.MultiSetSDKCallback
import com.multiset.sdk.MultiSetSDKConfig
import com.multiset.sdk.mapping.MultiSetMapping
import com.multiset.sdk.model.LocalizationMode
import com.multiset.sdk.model.LocalizationResult
import com.multiset.sdk.model.ObjectTrackingResult
import com.multiset.sdk.model.TrackingState
import com.multiset.xr.ui.MultiSetLocalizationActivity
import com.multiset.xr.ui.MultiSetLocalizationActivity.Companion.EXTRA_LOCALIZATION_MODE
import com.multiset.xr.ui.ObjectTrackingActivity
import com.multiset.xr.ui.ObjectTrackingActivity.Companion.EXTRA_OBJECT_CODES
import com.multiset.xr.SettingsDialogFragment
import com.multiset.xr.ui.mapping.DraftMapsActivity
import com.multiset.xr.ui.mapping.DraftMapsCopy
import com.multiset.xr.ui.mapping.MapScanActivity
import com.multiset.xr.ui.mapping.MappingInstructionsActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), MultiSetSDKCallback {

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_MODE = "localization_mode"
    }

    private lateinit var binding: ActivityMainBinding
    private var selectedMode: LocalizationMode = MultiSetSDKConfig.DEFAULT_LOCALIZATION_MODE
    private var mapping: MultiSetMapping? = null

    // A recreated screen (a rotation) re-registers with the SDK, which answers from the sign-in it has.
    private var announceSignIn = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)

        ConfigStore.load(this)
        announceSignIn = savedInstanceState == null
        savedInstanceState?.getString(STATE_MODE)
            ?.let { saved -> LocalizationMode.entries.firstOrNull { it.name == saved } }
            ?.let { selectedMode = it }
        val sdkReady = MultiSetSDKInit.initialize(this, this)
        if (!sdkReady) {
            showConfigurationAlert()
        }

        setupUI()
        setupMapping(sdkReady)
        displayMapCode()
        displayObjectCodes()

        binding.instructionsText.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = android.net.Uri.parse("https://developer.multiset.ai/credentials")
            startActivity(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, selectedMode.name)
    }

    // Uploads run in-process: returning to the app is when a parked or waiting draft tries again
    override fun onResume() {
        super.onResume()
        mapping?.resumeUploads()
    }

    private fun setupUI() {
        binding.localizationButton.isEnabled = false
        binding.objectTrackingButton.isEnabled = false
        binding.authButton.isEnabled = false

        binding.modeToggleGroup.check(
            if (selectedMode == LocalizationMode.SINGLE_FRAME) R.id.singleFrameButton else R.id.multiFrameButton
        )

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedMode = when (checkedId) {
                    R.id.singleFrameButton -> LocalizationMode.SINGLE_FRAME
                    else -> LocalizationMode.MULTI_FRAME
                }
            }
        }

        binding.settingsButton.setOnClickListener {
            SettingsDialogFragment.newInstance(selectedMode == LocalizationMode.MULTI_FRAME)
                .show(supportFragmentManager, SettingsDialogFragment.TAG)
        }

        binding.authButton.setOnClickListener {
            announceSignIn = true
            MultiSetSDKInit.initialize(this, this)
        }

        binding.localizationButton.setOnClickListener {
            val intent = Intent(this, MultiSetLocalizationActivity::class.java).apply {
                putExtra(EXTRA_LOCALIZATION_MODE, selectedMode.name)
            }
            startActivity(intent)
        }

        binding.objectTrackingButton.setOnClickListener {
            val codes = MultiSetSDKInit.objectCodes()
            if (codes.isEmpty()) {
                showToast("No object codes configured")
                return@setOnClickListener
            }
            val intent = Intent(this, ObjectTrackingActivity::class.java).apply {
                putExtra(EXTRA_OBJECT_CODES, codes.toTypedArray())
            }
            startActivity(intent)
        }
    }

    // Scanning needs only an initialized SDK; uploading waits for authentication on its own
    private fun setupMapping(sdkReady: Boolean) {
        binding.createMapButton.isEnabled = sdkReady
        binding.draftMapsButton.isEnabled = sdkReady
        binding.createMapButton.setOnClickListener {
            val first = if (ConfigStore.isMappingInstructionsHidden(this)) {
                MapScanActivity::class.java
            } else {
                MappingInstructionsActivity::class.java
            }
            startActivity(Intent(this, first))
        }
        binding.draftMapsButton.setOnClickListener {
            startActivity(Intent(this, DraftMapsActivity::class.java))
        }
        if (!sdkReady) return

        val mapping = MultiSetSDK.mapping.also { this.mapping = it }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mapping.drafts.collect { drafts ->
                    binding.mappingDraftBadge.isVisible = drafts.isNotEmpty()
                    binding.mappingDraftBadge.text = DraftMapsCopy.badge(drafts.size)
                }
            }
        }
    }

    private fun displayMapCode() {
        val mapCode = BuildConfig.MULTISET_MAP_CODE
        val mapSetCode = BuildConfig.MULTISET_MAP_SET_CODE

        when {
            mapCode.isEmpty() && mapSetCode.isEmpty() -> {
                binding.mapCodeText.visibility = View.GONE
                binding.noMapText.visibility = View.VISIBLE
            }
            else -> {
                binding.mapCodeText.visibility = View.VISIBLE
                binding.noMapText.visibility = View.GONE
                binding.mapCodeText.text = mapCode.ifEmpty { mapSetCode }
            }
        }
    }

    private fun displayObjectCodes() {
        val objectCodes = MultiSetSDKInit.objectCodes()

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
                    imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@MainActivity, R.color.accent_ink)
                    )
                    layoutParams = LinearLayout.LayoutParams(dpToPx(14), dpToPx(14))
                }

                val label = TextView(this).apply {
                    text = code
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_mid))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dpToPx(6)
                    }
                }

                row.addView(icon)
                row.addView(label)
                binding.objectCodesContainer.addView(row)
            }
        }
    }

    private fun showConfigurationAlert() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.configuration_required)
            .setMessage(R.string.configuration_required_body)
            .setPositiveButton(R.string.error_dismiss) { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // ── MultiSetSDKCallback ────────────────────────────────────────────────────

    override fun onSDKReady() {
        runOnUiThread {
            binding.authButton.text = getString(R.string.authenticating)
        }
    }

    override fun onAuthenticationSuccess() {
        runOnUiThread {
            binding.authButton.visibility = View.GONE
            binding.authStatusChip.visibility = View.VISIBLE
            binding.localizationButton.isEnabled = true
            binding.objectTrackingButton.isEnabled = true
            if (announceSignIn) showToast("Authentication successful")
            announceSignIn = false
        }
    }

    override fun onAuthenticationFailure(error: String) {
        runOnUiThread {
            binding.authButton.visibility = View.VISIBLE
            binding.authStatusChip.visibility = View.GONE
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
                "confidence: ${result.confidence}, " +
                "geoCoordinates: ${result.geoCoordinates}, " +
                "geoPose: ${result.geoPose}")
    }

    override fun onLocalizationFailure(error: String) {
        // AR activity handles localization failure internally
    }

    override fun onTrackingStateChanged(state: TrackingState) {
        // AR activity handles tracking state changes internally
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
}
