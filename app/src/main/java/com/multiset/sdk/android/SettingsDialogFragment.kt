/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.sdk.android

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt

/**
 * Full-screen settings window for the demo app, launched from the landing page.
 *
 * Lets the user adjust the behavioral parameters of [LocalizationConfig] and
 * [ObjectTrackingConfig], then Save (persisted via [ConfigStore]) or Reset to
 * defaults. The AR activities read these same config objects when localization
 * or tracking is started, so saved values take effect on the next run.
 */

class SettingsDialogFragment : DialogFragment() {

    /** Applies one input field back to its config object when Save is pressed. */
    private val savers = mutableListOf<() -> Unit>()

    private lateinit var mapContainer: LinearLayout
    private lateinit var objectContainer: LinearLayout

    /** Number of frames only applies to multi-frame localization. */
    private val isMultiFrame: Boolean
        get() = arguments?.getBoolean(ARG_MULTI_FRAME, true) ?: true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapContainer = view.findViewById(R.id.mapContainer)
        objectContainer = view.findViewById(R.id.objectContainer)

        populate()

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val footer = view.findViewById<View>(R.id.footer)
        toolbar.setNavigationOnClickListener { dismiss() }
        view.findViewById<View>(R.id.saveButton).setOnClickListener { onSave() }
        view.findViewById<View>(R.id.resetButton).setOnClickListener { onReset() }

        // Keep the toolbar below the status bar and the footer above the nav bar.
        val toolbarPaddingTop = toolbar.paddingTop
        val footerPaddingBottom = footer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            toolbar.updatePadding(top = toolbarPaddingTop + bars.top)
            footer.updatePadding(bottom = footerPaddingBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onStart() {
        super.onStart()
        // Full-screen window over the landing page.
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    /** (Re)build all rows from the current config values. */
    private fun populate() {
        savers.clear()
        mapContainer.removeAllViews()
        objectContainer.removeAllViews()

        // ---- Map Localization ----
        with(LocalizationConfig) {
            switchRow(mapContainer, "Auto Localize", autoLocalize) { autoLocalize = it }
            switchRow(mapContainer, "Background Localization", backgroundLocalization) { backgroundLocalization = it }
            sliderRow(mapContainer, "Background Interval", 15f, 180f, 1f, backgroundLocalizationIntervalSeconds, { "${it.toInt()} s" }) {
                backgroundLocalizationIntervalSeconds = it
            }
            switchRow(mapContainer, "Relocalization (on tracking loss)", relocalization) { relocalization = it }
            switchRow(mapContainer, "First Localization Until Success", firstLocalizationUntilSuccess) { firstLocalizationUntilSuccess = it }
            if (isMultiFrame) {
                sliderRow(mapContainer, "Number of Frames (multi-frame)", 4f, 6f, 1f, numberOfFrames.toFloat(), { it.toInt().toString() }) {
                    numberOfFrames = it.toInt()
                }
            }
            switchRow(mapContainer, "Confidence Check", confidenceCheck) { confidenceCheck = it }
            sliderRow(mapContainer, "Confidence Threshold", 0.2f, 0.8f, 0.05f, confidenceThreshold, { "%.2f".format(it) }) {
                confidenceThreshold = it
            }
            switchRow(mapContainer, "Enable Geo Hint (GPS)", enableGeoHint) { enableGeoHint = it }
            switchRow(mapContainer, "Include Geo Coordinates In Response", includeGeoCoordinatesInResponse) { includeGeoCoordinatesInResponse = it }
            sliderRow(mapContainer, "Hint Radius", 1f, 100f, 1f, hintRadius.toFloat(), { "${it.toInt()} m" }) {
                hintRadius = it.toInt()
            }
            switchRow(mapContainer, "Use 2D Filtering", use2DFiltering) { use2DFiltering = it }
            textRow(mapContainer, "Hint Map Codes (comma separated)", "MAP_A, MAP_B", hintMapCodes.joinToString(", "), InputType.TYPE_CLASS_TEXT) {
                hintMapCodes = it.split(",").map { c -> c.trim() }.filter { c -> c.isNotEmpty() }
            }
            textRow(mapContainer, "Hint Position (x,y,z)", "12.5,0.0,-3.2", hintPosition, InputType.TYPE_CLASS_TEXT) { hintPosition = it.trim() }
            textRow(mapContainer, "Hint Floor Height (floor,ceiling)", "0,5", hintFloorHeight, InputType.TYPE_CLASS_TEXT) { hintFloorHeight = it.trim() }
        }

        // ---- Object Tracking ----
        with(ObjectTrackingConfig) {
            switchRow(objectContainer, "Auto Tracking", autoTracking) { autoTracking = it }
            switchRow(objectContainer, "Background Tracking", backgroundTracking) { backgroundTracking = it }
            sliderRow(objectContainer, "Background Duration", 5f, 30f, 1f, bgTrackingDurationSeconds, { "${it.toInt()} s" }) {
                bgTrackingDurationSeconds = it
            }
            switchRow(objectContainer, "Restart Tracking (on tracking loss)", restartTracking) { restartTracking = it }
            switchRow(objectContainer, "First Tracking Until Success", firstTrackingUntilSuccess) { firstTrackingUntilSuccess = it }
        }
    }

    private fun onSave() {
        savers.forEach { it() }
        LocalizationConfig.validate()
        ObjectTrackingConfig.validate()
        ConfigStore.save(requireContext())
        Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
        dismiss()
    }

    private fun onReset() {
        ConfigStore.resetToDefaults(requireContext())
        populate()
        Toast.makeText(requireContext(), "Reset to defaults", Toast.LENGTH_SHORT).show()
    }

    // ==================== Row builders ====================

    private fun switchRow(
        parent: LinearLayout,
        label: String,
        initial: Boolean,
        setter: (Boolean) -> Unit
    ) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = rowParams()
        }
        val text = TextView(requireContext()).apply {
            text = label
            textSize = 15f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switch = SwitchMaterial(requireContext()).apply { isChecked = initial }
        row.addView(text)
        row.addView(switch)
        parent.addView(row)
        savers.add { setter(switch.isChecked) }
    }

    /**
     * A labelled Material slider whose current value is shown to the right of the
     * label. The slider physically constrains input to [from]..[to] in [step]
     * increments, so out-of-range values are impossible.
     */
    private fun sliderRow(
        parent: LinearLayout,
        label: String,
        from: Float,
        to: Float,
        step: Float,
        initial: Float,
        format: (Float) -> String,
        setter: (Float) -> Unit
    ) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = rowParams()
        }
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val snapped = snap(initial, from, to, step)
        val labelView = TextView(requireContext()).apply {
            text = label
            textSize = 15f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(requireContext()).apply {
            text = format(snapped)
            textSize = 15f
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(labelView)
        header.addView(valueView)

        val slider = Slider(requireContext()).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            value = snapped
            addOnChangeListener { _, v, _ -> valueView.text = format(v) }
        }
        row.addView(header)
        row.addView(slider)
        parent.addView(row)
        savers.add { setter(slider.value) }
    }

    /**
     * A Material outlined text field. [label] is the floating hint; [example] is
     * shown as a placeholder only while the field is focused and empty, so it
     * never overlaps the label.
     */
    private fun textRow(
        parent: LinearLayout,
        label: String,
        example: String,
        initial: String,
        inputType: Int,
        setter: (String) -> Unit
    ) {
        val til = TextInputLayout(requireContext()).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            if (example.isNotEmpty()) placeholderText = example
            layoutParams = rowParams()
        }
        val edit = TextInputEditText(til.context).apply {
            setText(initial)
            this.inputType = inputType
            setSingleLine(true)
        }
        til.addView(edit)
        parent.addView(til)
        savers.add { setter(edit.text?.toString().orEmpty()) }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dpToPx(12) }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(requireContext(), tv.resourceId)
        } else {
            tv.data
        }
    }

    /** Coerce into range and snap to the nearest step so Slider.value is always valid. */
    private fun snap(value: Float, from: Float, to: Float, step: Float): Float {
        val clamped = value.coerceIn(from, to)
        val steps = ((clamped - from) / step).roundToInt()
        return (from + steps * step).coerceIn(from, to)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "SettingsDialog"
        private const val ARG_MULTI_FRAME = "multiFrame"

        fun newInstance(isMultiFrame: Boolean) = SettingsDialogFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_MULTI_FRAME, isMultiFrame) }
        }
    }
}
