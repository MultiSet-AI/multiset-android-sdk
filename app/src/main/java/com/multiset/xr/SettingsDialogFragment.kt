/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr

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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.MultiSetSDKConfig
import com.multiset.sdk.model.QueryMode
import com.multiset.xr.config.ConfigStore
import com.multiset.xr.config.LocalizationConfig
import com.multiset.xr.config.ObjectTrackingConfig
import kotlin.math.roundToInt

class SettingsDialogFragment : DialogFragment() {

    private val savers = mutableListOf<() -> Unit>()
    private lateinit var mapContainer: LinearLayout
    private lateinit var objectContainer: LinearLayout
    private lateinit var environmentContainer: LinearLayout

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
        environmentContainer = view.findViewById(R.id.environmentContainer)

        populate()

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val footer = view.findViewById<View>(R.id.footer)
        toolbar.setNavigationOnClickListener { dismiss() }
        view.findViewById<View>(R.id.saveButton).setOnClickListener { onSave() }
        view.findViewById<View>(R.id.resetButton).setOnClickListener { onReset() }

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
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun populate() {
        savers.clear()
        mapContainer.removeAllViews()
        objectContainer.removeAllViews()
        environmentContainer.removeAllViews()

        with(LocalizationConfig) {
            if (!isMultiFrame) {
                dropdownRow(
                    mapContainer, "Query Mode (single-frame)", QUERY_MODE_LABELS,
                    queryMode, QUERY_MODE_DESCRIPTIONS
                ) { queryMode = it }
            }
            switchRow(mapContainer, "Auto Localize", autoLocalize) { autoLocalize = it }
            gatedSliderRow(
                mapContainer,
                switchLabel = "Background Localization", switchInitial = backgroundLocalization,
                switchSetter = { backgroundLocalization = it },
                sliderLabel = "Background Interval", from = 15f, to = 180f, step = 1f,
                sliderInitial = backgroundLocalizationIntervalSeconds, format = { "${it.toInt()} s" },
            ) { backgroundLocalizationIntervalSeconds = it }
            switchRow(mapContainer, "Relocalization (on tracking loss)", relocalization) { relocalization = it }
            switchRow(mapContainer, "First Localization Until Success", firstLocalizationUntilSuccess) { firstLocalizationUntilSuccess = it }
            if (isMultiFrame) {
                sliderRow(mapContainer, "Number of Frames (multi-frame)", 4f, 6f, 1f, numberOfFrames.toFloat(), { it.toInt().toString() }) {
                    numberOfFrames = it.toInt()
                }
            }
            gatedSliderRow(
                mapContainer,
                switchLabel = "Confidence Check", switchInitial = confidenceCheck,
                switchSetter = { confidenceCheck = it },
                sliderLabel = "Confidence Threshold", from = 0.2f, to = 0.8f, step = 0.05f,
                sliderInitial = confidenceThreshold, format = { "%.2f".format(it) },
            ) { confidenceThreshold = it }
            gatedSection(
                mapContainer,
                switchLabel = "Pose Consistency Check", switchInitial = poseConsistencyCheck,
                switchSetter = { poseConsistencyCheck = it },
            ) { p ->
                listOf(
                    sliderRow(
                        p, "Distance Limit", 1.5f, 15f, 0.5f,
                        poseConsistencyThreshold, { "%.1f m".format(it) },
                    ) { poseConsistencyThreshold = it },
                    sliderRow(
                        p, "Heading Limit", 10f, 60f, 5f,
                        poseConsistencyYawThreshold, { "${it.toInt()}°" },
                    ) { poseConsistencyYawThreshold = it },
                )
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

        populateEnvironment()

        with(ObjectTrackingConfig) {
            switchRow(objectContainer, "Auto Tracking", autoTracking) { autoTracking = it }
            gatedSliderRow(
                objectContainer,
                switchLabel = "Background Tracking", switchInitial = backgroundTracking,
                switchSetter = { backgroundTracking = it },
                sliderLabel = "Background Duration", from = 5f, to = 30f, step = 1f,
                sliderInitial = bgTrackingDurationSeconds, format = { "${it.toInt()} s" },
            ) { bgTrackingDurationSeconds = it }
            switchRow(objectContainer, "Restart Tracking (on tracking loss)", restartTracking) { restartTracking = it }
            switchRow(objectContainer, "First Tracking Until Success", firstTrackingUntilSuccess) { firstTrackingUntilSuccess = it }
        }
    }

    /**
     * Read-only: which deployment this build talks to. Not editable here because the SDK
     * reads the host once, before authenticating — it comes from MULTISET_BASE_URL.
     */
    private fun populateEnvironment() {
        val baseUrl = activeBaseUrl()
        readOnlyRow(environmentContainer, getString(R.string.settings_base_url_label), baseUrl)
        captionRow(
            environmentContainer,
            if (baseUrl == MultiSetSDKConfig.DEFAULT_BASE_URL) getString(R.string.settings_base_url_production)
            else getString(R.string.settings_base_url_custom),
        )
    }

    /**
     * The live SDK value, except before [MultiSetSDKInit] has run — initialization bails out
     * early on missing credentials, and reporting the production default then would
     * contradict the MULTISET_BASE_URL this card tells the user to edit.
     */
    private fun activeBaseUrl(): String {
        val override = BuildConfig.MULTISET_BASE_URL.trim()
        return if (override.isEmpty()) MultiSetSDK.activeBaseUrl
        else MultiSetSDKConfig.normalizeBaseUrl(override)
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

    private fun switchRow(
        parent: LinearLayout, label: String, initial: Boolean,
        onChange: ((Boolean) -> Unit)? = null, setter: (Boolean) -> Unit
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
        val switch = SwitchMaterial(requireContext()).apply {
            isChecked = initial
            onChange?.let { listener -> setOnCheckedChangeListener { _, checked -> listener(checked) } }
        }
        row.addView(text)
        row.addView(switch)
        parent.addView(row)
        savers.add { setter(switch.isChecked) }
    }

    /**
     * A switch and the rows it reveals. The rows are hidden while the switch is off, and still
     * save their values when hidden so turning the switch back on restores them.
     */
    private fun gatedSection(
        parent: LinearLayout,
        switchLabel: String, switchInitial: Boolean, switchSetter: (Boolean) -> Unit,
        rows: (LinearLayout) -> List<View>,
    ) {
        lateinit var gated: List<View>
        switchRow(
            parent, switchLabel, switchInitial,
            onChange = { on ->
                val visibility = if (on) View.VISIBLE else View.GONE
                gated.forEach { it.visibility = visibility }
            },
            setter = switchSetter,
        )
        gated = rows(parent)
        val visibility = if (switchInitial) View.VISIBLE else View.GONE
        gated.forEach { it.visibility = visibility }
    }

    private fun gatedSliderRow(
        parent: LinearLayout,
        switchLabel: String, switchInitial: Boolean, switchSetter: (Boolean) -> Unit,
        sliderLabel: String, from: Float, to: Float, step: Float,
        sliderInitial: Float, format: (Float) -> String, sliderSetter: (Float) -> Unit,
    ) = gatedSection(parent, switchLabel, switchInitial, switchSetter) { p ->
        listOf(sliderRow(p, sliderLabel, from, to, step, sliderInitial, format, sliderSetter))
    }

    private fun sliderRow(
        parent: LinearLayout, label: String, from: Float, to: Float, step: Float,
        initial: Float, format: (Float) -> String, setter: (Float) -> Unit
    ): View {
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
        return row
    }

    private fun textRow(
        parent: LinearLayout, label: String, example: String,
        initial: String, inputType: Int, setter: (String) -> Unit
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

    private fun <T> dropdownRow(
        parent: LinearLayout, label: String, options: Map<T, String>,
        initial: T, descriptions: Map<T, String> = emptyMap(), setter: (T) -> Unit
    ) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = rowParams()
        }
        val til = layoutInflater.inflate(R.layout.row_dropdown, row, false) as TextInputLayout
        til.hint = label
        val input = til.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownInput)
        val values = options.keys.toList()
        input.setSimpleItems(options.values.toTypedArray())
        input.setText(options[initial], false)

        val description = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(6) }
        }
        fun describe(value: T) {
            val text = descriptions[value]
            description.text = text.orEmpty()
            description.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        describe(initial)

        var selected = initial
        input.setOnItemClickListener { _, _, position, _ ->
            selected = values[position]
            describe(selected)
        }

        row.addView(til)
        row.addView(description)
        parent.addView(row)
        savers.add { setter(selected) }
    }

    /** Label left, value right in monospace — no saver: nothing here is editable. */
    private fun readOnlyRow(parent: LinearLayout, label: String, value: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = rowParams()
        }
        val labelView = TextView(requireContext()).apply {
            text = label
            textSize = 15f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpToPx(12) }
        }
        val valueView = TextView(requireContext()).apply {
            text = value
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            textAlignment = View.TEXT_ALIGNMENT_TEXT_END
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(labelView)
        row.addView(valueView)
        parent.addView(row)
    }

    private fun captionRow(parent: LinearLayout, text: String) {
        parent.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
        })
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dpToPx(12) }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(requireContext(), tv.resourceId)
        else tv.data
    }

    private fun snap(value: Float, from: Float, to: Float, step: Float): Float {
        val clamped = value.coerceIn(from, to)
        val steps = ((clamped - from) / step).roundToInt()
        return (from + steps * step).coerceIn(from, to)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "SettingsDialog"

        // Labels match the Unity SDK inspector so the two SDKs read the same.
        private val QUERY_MODE_LABELS = linkedMapOf(
            QueryMode.VPS1 to "VPS-1 (Standard)",
            QueryMode.VPS2 to "VPS-2 (Deep Search)",
        )

        private val QUERY_MODE_DESCRIPTIONS = mapOf(
            QueryMode.VPS1 to "Standard matching. Fastest response, best for well-mapped areas.",
            QueryMode.VPS2 to "Deep search. Slower, but recovers a pose in harder or sparsely " +
                "mapped areas. Single-frame only — multi-frame localization always uses VPS-1.",
        )

        private const val ARG_MULTI_FRAME = "multiFrame"

        fun newInstance(isMultiFrame: Boolean) = SettingsDialogFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_MULTI_FRAME, isMultiFrame) }
        }
    }
}
