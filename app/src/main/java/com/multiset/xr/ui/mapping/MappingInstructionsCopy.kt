/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.mapping

/** The five scanning instructions shown before a scan. */
object MappingInstructionsCopy {
    /** One instruction: a short [tag] and the advice itself. */
    data class Page(val tag: String, val text: String)

    const val TITLE = "Before you scan"
    const val SKIP = "Skip"
    const val PREVIOUS = "Previous"
    const val NEXT = "Next"
    const val START = "Start scanning"
    const val DONT_SHOW_AGAIN = "Don't show this again"
    const val CLOSE = "Close"
    const val SKIP_HINT = "Skips the instructions and starts the scan"

    val pages = listOf(
        Page("Distance", "Move slowly and capture the object from 1–5 meters of distance."),
        Page("Coverage", "Cover an area only once — don't capture the same area multiple times in a session."),
        Page("Pacing", "Move at a steady speed in a continuous up-and-down motion for best results."),
        Page("Stability", "Avoid rapid movements or rotations to prevent warped reconstructions."),
        Page("Vertical sweep", "To capture floor and ceiling, move the device up and down."),
    )

    fun counter(index: Int) = "${index + 1}/${pages.size}"

    fun stepDescription(index: Int): String {
        val page = pages[index]
        return "Step ${index + 1} of ${pages.size}, ${page.tag}. ${page.text}"
    }
}
