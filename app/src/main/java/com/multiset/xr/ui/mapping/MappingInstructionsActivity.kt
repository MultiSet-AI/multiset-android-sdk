/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.mapping

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import com.multiset.xr.R
import com.multiset.xr.config.ConfigStore
import com.multiset.xr.databinding.ActivityMappingInstructionsBinding
import com.multiset.xr.ui.padForSystemBars
import kotlin.math.abs

/** The five scanning instructions, shown before a scan unless the user asked not to see them again. */
class MappingInstructionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMappingInstructionsBinding
    private lateinit var texts: List<View>
    private lateinit var dots: List<View>
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMappingInstructionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)

        binding.titleText.text = MappingInstructionsCopy.TITLE
        binding.closeButton.contentDescription = MappingInstructionsCopy.CLOSE
        binding.skipButton.text = MappingInstructionsCopy.SKIP
        binding.skipButton.contentDescription = "${MappingInstructionsCopy.SKIP}. ${MappingInstructionsCopy.SKIP_HINT}"
        binding.previousButton.text = MappingInstructionsCopy.PREVIOUS
        binding.nextButton.text = MappingInstructionsCopy.NEXT
        binding.startButton.text = MappingInstructionsCopy.START
        binding.dontShowAgain.text = MappingInstructionsCopy.DONT_SHOW_AGAIN

        texts = MappingInstructionsCopy.pages.indices.map(::instructionText)
        texts.forEach(binding.textStrip::addView)
        dots = MappingInstructionsCopy.pages.indices.map { pagerDot() }
        dots.forEach(binding.dots::addView)

        binding.closeButton.setOnClickListener { finish() }
        binding.skipButton.setOnClickListener { startScan() }
        binding.startButton.setOnClickListener { startScan() }
        binding.previousButton.setOnClickListener { show(page - 1) }
        binding.nextButton.setOnClickListener { show(page + 1) }
        binding.dontShowAgain.isChecked = ConfigStore.isMappingInstructionsHidden(this)
        binding.dontShowAgain.setOnCheckedChangeListener { _, checked ->
            ConfigStore.setMappingInstructionsHidden(this, checked)
        }
        listenForSwipes()

        show(savedInstanceState?.getInt(KEY_PAGE) ?: 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, page)
    }

    private fun show(index: Int) {
        page = index.coerceIn(0, MappingInstructionsCopy.pages.lastIndex)
        binding.illustration.setImageResource(IMAGES[page])
        binding.illustrationCard.contentDescription = MappingInstructionsCopy.stepDescription(page)
        // Every page's text stays laid out, invisibly, so the strip keeps the tallest one's height
        texts.forEachIndexed { i, view -> view.isInvisible = i != page }
        dots.forEachIndexed { i, dot ->
            val active = i == page
            dot.backgroundTintList = ContextCompat.getColorStateList(this, if (active) R.color.brand_ink else R.color.outline)
            dot.updateLayoutParams { width = dp(if (active) ACTIVE_DOT_DP else DOT_DP) }
        }
        binding.previousButton.isEnabled = page > 0
        binding.nextButton.isEnabled = page < MappingInstructionsCopy.pages.lastIndex
    }

    private fun startScan() {
        startActivity(Intent(this, MapScanActivity::class.java))
        finish()
    }

    // Swiping is a shortcut; Previous and Next are the accessible way to turn the page
    @SuppressLint("ClickableViewAccessibility")
    private fun listenForSwipes() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (abs(velocityX) < abs(velocityY)) return false
                show(if (velocityX < 0) page + 1 else page - 1)
                return true
            }
        })
        binding.illustration.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
        }
    }

    private fun instructionText(index: Int): View {
        val page = MappingInstructionsCopy.pages[index]
        val counter = TextView(this).apply {
            text = MappingInstructionsCopy.counter(index)
            textSize = CAPTION_SP
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.brand_ink))
        }
        val tag = TextView(this).apply {
            text = page.tag
            textSize = CAPTION_SP
            isAllCaps = true
            letterSpacing = EYEBROW_TRACKING
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_low))
            setPaddingRelative(dp(TAG_GAP_DP), 0, 0, 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(counter)
            addView(tag)
        }
        val body = TextView(this).apply {
            text = page.text
            textSize = BODY_SP
            setTextColor(ContextCompat.getColor(context, R.color.text_hi))
            setPadding(0, dp(TEXT_GAP_DP), 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(header)
            addView(body)
        }
    }

    private fun pagerDot() = View(this).apply {
        setBackgroundResource(R.drawable.bg_pill)
        layoutParams = LinearLayout.LayoutParams(dp(DOT_DP), dp(DOT_DP)).apply {
            marginStart = dp(DOT_GAP_DP) / 2
            marginEnd = dp(DOT_GAP_DP) / 2
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val IMAGES = intArrayOf(
            R.drawable.instruction_01,
            R.drawable.instruction_02,
            R.drawable.instruction_03,
            R.drawable.instruction_04,
            R.drawable.instruction_05,
        )
        const val KEY_PAGE = "page"
        const val DOT_DP = 8
        const val ACTIVE_DOT_DP = 28
        const val DOT_GAP_DP = 8
        const val TAG_GAP_DP = 8
        const val TEXT_GAP_DP = 8
        const val CAPTION_SP = 13f
        const val BODY_SP = 17f
        const val EYEBROW_TRACKING = 0.08f
    }
}
