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
import com.multiset.xr.config.ConfigStore
import com.multiset.xr.ui.padForSystemBars
import com.multiset.xr.databinding.ActivityMainBinding
import com.multiset.sdk.MultiSetSDKCallback
import com.multiset.sdk.model.LocalizationMode
import com.multiset.sdk.model.LocalizationResult
import com.multiset.sdk.model.ObjectTrackingResult
import com.multiset.sdk.model.TrackingState
import com.multiset.xr.ui.MultiSetLocalizationActivity
import com.multiset.xr.ui.MultiSetLocalizationActivity.Companion.EXTRA_LOCALIZATION_MODE
import com.multiset.xr.ui.ObjectTrackingActivity
import com.multiset.xr.ui.ObjectTrackingActivity.Companion.EXTRA_OBJECT_CODES
import com.multiset.xr.SettingsDialogFragment

class MainActivity : AppCompatActivity(), MultiSetSDKCallback {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var selectedMode: LocalizationMode = LocalizationMode.MULTI_FRAME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)

        ConfigStore.load(this)
        val sdkReady = MultiSetSDKInit.initialize(this, this)
        if (!sdkReady) {
            showConfigurationAlert()
        }

        setupUI()
        displayMapCode()
        displayObjectCodes()

        binding.instructionsText.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = android.net.Uri.parse("https://developer.multiset.ai/credentials")
            startActivity(intent)
        }
    }

    private fun setupUI() {
        binding.localizationButton.isEnabled = false
        binding.objectTrackingButton.isEnabled = false
        binding.authButton.isEnabled = false

        // Default mode = Multi Frame (matching reference default)
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
            SettingsDialogFragment.newInstance(selectedMode == LocalizationMode.MULTI_FRAME)
                .show(supportFragmentManager, SettingsDialogFragment.TAG)
        }

        binding.authButton.setOnClickListener {
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
            showToast("Authentication successful")
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
                "confidence: ${result.confidence}")
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
