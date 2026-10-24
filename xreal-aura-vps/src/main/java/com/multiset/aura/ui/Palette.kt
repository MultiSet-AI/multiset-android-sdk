/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.ui

import androidx.compose.ui.graphics.Color

/** The app's chrome colours: dark surfaces that read over any camera image. */
object Palette {
    val Rail = Color(0xFF111318)
    val Card = Color(0xFF1B1E25)
    val CardRaised = Color(0xFF252933)
    val Glass = Color.Black.copy(alpha = 0.6f)
    val Text = Color.White
    val TextSecondary = Color.White.copy(alpha = 0.62f)
    val TextDisabled = Color.White.copy(alpha = 0.38f)
    val Outline = Color.White.copy(alpha = 0.14f)
    val Accent = Color(0xFF3D7BFF)
    val Success = Color(0xFF34C759)
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF453A)
    val Stop = Color(0xFFE5352B)
    val Idle = Color(0xFF8E8E93)
}
