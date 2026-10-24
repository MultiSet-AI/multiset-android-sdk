/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.localization

/** Feedback shown to the wearer after a localization attempt. */
sealed interface Banner {
    data class Success(val label: String, val confidence: Float?) : Banner
    data class Failure(val message: String) : Banner
    data class Warning(val message: String) : Banner
}

/**
 * The banner a state change deserves, if any. Background fixes and failures stay quiet; a fix or a
 * failure is announced when it is the first since a reset or when the wearer pressed Localize for it.
 */
fun nextBanner(previous: LocalizationState, current: LocalizationState, userRequested: Boolean): Banner? {
    val phase = current.phase
    val rejected = current.notices[Notice.REJECTED_FIX]
    val failedWhileLocalized = current.notices[Notice.BACKGROUND_FAILURE]
    return when {
        phase is LocalizationPhase.Localized && current.fixes > previous.fixes &&
            (previous.phase !is LocalizationPhase.Localized || userRequested) ->
            Banner.Success(phase.mapCode, phase.confidence)
        phase is LocalizationPhase.Failed && phase != previous.phase -> Banner.Failure(phase.message)
        userRequested && failedWhileLocalized != null &&
            failedWhileLocalized != previous.notices[Notice.BACKGROUND_FAILURE] -> Banner.Failure(failedWhileLocalized)
        rejected != null && rejected != previous.notices[Notice.REJECTED_FIX] -> Banner.Warning(rejected)
        else -> null
    }
}

fun Banner.autoDismissMs(): Long? = when (this) {
    is Banner.Success -> 4_000L
    is Banner.Failure -> null
    is Banner.Warning -> 6_000L
}

/** Decides banners across state changes, remembering whether the wearer asked for the current attempt. */
class BannerDecider {
    private var userRequested = false

    fun onLocalizeRequested() {
        userRequested = true
    }

    fun next(previous: LocalizationState, current: LocalizationState): Banner? =
        nextBanner(previous, current, userRequested)?.also { userRequested = false }
}
