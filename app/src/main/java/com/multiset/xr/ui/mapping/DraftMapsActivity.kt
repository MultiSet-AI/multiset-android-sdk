/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.mapping

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.mapping.MappingDraft
import com.multiset.sdk.mapping.MappingUploadState
import com.multiset.sdk.mapping.MultiSetMapping
import com.multiset.xr.R
import com.multiset.xr.databinding.ActivityDraftMapsBinding
import com.multiset.xr.databinding.RowDraftMapBinding
import com.multiset.xr.ui.padForSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Scans saved on this device and not yet uploaded, each with its upload state and actions. */
class DraftMapsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDraftMapsBinding
    private lateinit var mapping: MultiSetMapping
    private val rows = LinkedHashMap<String, DraftRow>()
    private val thumbnails = HashMap<String, Bitmap?>()
    private val decoding = HashSet<String>()
    private var deleteDialog: AlertDialog? = null
    private var thumbnailWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDraftMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.padForSystemBars(top = true)
        binding.scroll.padForSystemBars(bottom = true)

        mapping = runCatching { MultiSetSDK.mapping }.getOrElse {
            finish()
            return
        }
        thumbnailWidth = resources.getDimensionPixelSize(R.dimen.draft_thumbnail_width)
        binding.toolbar.title = DraftMapsCopy.TITLE
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.emptyTitle.text = DraftMapsCopy.EMPTY_TITLE
        binding.emptyMessage.text = DraftMapsCopy.EMPTY_MESSAGE

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(mapping.drafts, mapping.uploads) { drafts, uploads -> drafts to uploads }
                    .collect { (drafts, uploads) -> render(drafts, uploads) }
            }
        }
    }

    // Coming back to the list is the moment to retry anything parked or waiting
    override fun onResume() {
        super.onResume()
        if (::mapping.isInitialized) mapping.resumeUploads()
    }

    override fun onDestroy() {
        deleteDialog?.dismiss()
        super.onDestroy()
    }

    // Rows are kept per draft id, so progress updates rebind text instead of rebuilding the list
    private fun render(drafts: List<MappingDraft>, uploads: Map<String, MappingUploadState>) {
        binding.emptyState.isVisible = drafts.isEmpty()
        binding.headerText.isVisible = drafts.isNotEmpty()
        binding.headerText.text = DraftMapsCopy.header(drafts.size)

        val ids = drafts.map { it.id }
        rows.keys.retainAll(ids.toSet())
        thumbnails.keys.retainAll(ids.toSet())
        drafts.forEach { draft -> rows.getOrPut(draft.id) { DraftRow(draft.id) }.bind(draft, uploads[draft.id]) }
        if (ids != rowOrder()) {
            binding.draftList.removeAllViews()
            ids.forEach { binding.draftList.addView(rows.getValue(it).view.root) }
        }
    }

    private fun rowOrder(): List<String> = (0 until binding.draftList.childCount).map { binding.draftList.getChildAt(it).tag as String }

    private fun loadThumbnail(draft: MappingDraft) {
        if (thumbnails.containsKey(draft.id) || !decoding.add(draft.id)) return
        val path = draft.thumbnailPath
        lifecycleScope.launch {
            val bitmap = path?.let { withContext(Dispatchers.IO) { runCatching { DraftThumbnails.decode(it, thumbnailWidth) }.getOrNull() } }
            decoding.remove(draft.id)
            // A draft deleted while it decoded has no row left; caching it would outlive the draft
            val row = rows[draft.id] ?: return@launch
            thumbnails[draft.id] = bitmap
            row.showThumbnail(bitmap)
        }
    }

    private fun confirmDelete(id: String) {
        deleteDialog = MaterialAlertDialogBuilder(this)
            .setTitle(DraftMapsCopy.DELETE_TITLE)
            .setMessage(DraftMapsCopy.DELETE_MESSAGE)
            .setPositiveButton(DraftMapsCopy.Action.DELETE.label) { _, _ -> mapping.deleteDraft(id) }
            .setNegativeButton(DraftMapsCopy.KEEP, null)
            .show()
    }

    private fun onUploadAction(id: String, action: DraftMapsCopy.Action) {
        when (action) {
            DraftMapsCopy.Action.UPLOAD, DraftMapsCopy.Action.RETRY_NOW -> mapping.upload(id)
            DraftMapsCopy.Action.TRY_AGAIN -> {
                mapping.dismissUploadFailure(id)
                mapping.upload(id)
            }
            DraftMapsCopy.Action.DELETE -> confirmDelete(id)
        }
    }

    private inner class DraftRow(private val id: String) {
        val view: RowDraftMapBinding = RowDraftMapBinding.inflate(layoutInflater, binding.draftList, false)
        private var bound: Pair<MappingDraft, MappingUploadState?>? = null

        init {
            view.root.tag = id
            view.deleteButton.text = DraftMapsCopy.Action.DELETE.label
            view.deleteButton.setOnClickListener { confirmDelete(id) }
            showThumbnail(thumbnails[id])
        }

        fun bind(draft: MappingDraft, state: MappingUploadState?) {
            if (bound == draft to state) return
            bound = draft to state
            loadThumbnail(draft)

            view.nameText.text = draft.name
            val facts = DraftMapsCopy.facts(draft)
            view.factsText.text = facts ?: DraftMapsCopy.PACKAGE_MISSING
            view.factsText.setTextColor(color(if (facts == null) R.color.danger_ink else R.color.text_mid))
            val scanned = DraftMapsCopy.scanned(draft.packagedAtMillis)
            view.scannedText.isVisible = scanned != null
            view.scannedText.text = scanned

            view.statusPill.text = DraftMapsCopy.pill(state)
            val (fill, ink) = toneColors(DraftMapsCopy.tone(state))
            view.statusPill.backgroundTintList = ContextCompat.getColorStateList(this@DraftMapsActivity, fill)
            view.statusPill.setTextColor(color(ink))

            val note = DraftMapsCopy.note(state)
            view.noteText.isVisible = note != null
            view.noteText.text = note
            view.noteText.setTextColor(color(if (state is MappingUploadState.Failed) R.color.danger_ink else R.color.text_mid))
            view.noteText.leadingIcon(noteIcon(state), if (state is MappingUploadState.Failed) R.color.danger_ink else R.color.text_mid)
            val uploading = state as? MappingUploadState.Uploading
            view.progress.isVisible = uploading != null
            uploading?.let { view.progress.setProgressCompat((it.fraction * PROGRESS_MAX).toInt(), false) }

            val actions = DraftMapsCopy.actions(state)
            view.actions.isVisible = actions.isNotEmpty()
            val upload = actions.firstOrNull { it != DraftMapsCopy.Action.DELETE }
            view.uploadButton.isVisible = upload != null
            if (upload != null) {
                view.uploadButton.text = upload.label
                view.uploadButton.setIconResource(if (upload == DraftMapsCopy.Action.UPLOAD) R.drawable.ic_cloud else R.drawable.ic_refresh)
                view.uploadButton.setOnClickListener { onUploadAction(id, upload) }
            }
            view.deleteButton.isVisible = DraftMapsCopy.Action.DELETE in actions
        }

        fun showThumbnail(bitmap: Bitmap?) {
            val image = view.thumbnail
            if (bitmap != null) {
                image.imageTintList = null
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setImageBitmap(bitmap)
            } else {
                image.scaleType = ImageView.ScaleType.CENTER
                image.setImageResource(R.drawable.ic_image)
                image.imageTintList = ContextCompat.getColorStateList(this@DraftMapsActivity, R.color.text_low)
            }
        }
    }

    private fun TextView.leadingIcon(@DrawableRes icon: Int?, @ColorRes tint: Int) {
        val drawable = icon?.let { AppCompatResources.getDrawable(context, it)?.mutate() }?.apply {
            val size = (NOTE_ICON_DP * resources.displayMetrics.density).toInt()
            setBounds(0, 0, size, size)
            setTint(color(tint))
        }
        compoundDrawablePadding = (NOTE_ICON_GAP_DP * resources.displayMetrics.density).toInt()
        setCompoundDrawablesRelative(drawable, null, null, null)
    }

    @DrawableRes
    private fun noteIcon(state: MappingUploadState?): Int? = when (state) {
        MappingUploadState.Preparing -> R.drawable.ic_sync
        is MappingUploadState.Waiting -> R.drawable.ic_history
        is MappingUploadState.Failed -> R.drawable.ic_warning
        is MappingUploadState.Uploading, null -> null
    }

    private fun toneColors(tone: DraftMapsCopy.Tone): Pair<Int, Int> = when (tone) {
        DraftMapsCopy.Tone.NEUTRAL -> R.color.surface_variant to R.color.text_mid
        DraftMapsCopy.Tone.POSITIVE -> R.color.success_container to R.color.success_ink
        DraftMapsCopy.Tone.CAUTION -> R.color.mapping_container to R.color.caution_ink
        DraftMapsCopy.Tone.NEGATIVE -> R.color.danger_container to R.color.danger_ink
    }

    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    private companion object {
        const val PROGRESS_MAX = 1000
        const val NOTE_ICON_DP = 14
        const val NOTE_ICON_GAP_DP = 6
    }
}
