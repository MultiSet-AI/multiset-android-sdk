/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.multiset.aura.R
import com.multiset.sdk.model.MultiSetError

/**
 * Presents an actionable failure (rejected request, bad credentials, unreachable API) as a modal
 * alert carrying the API's own explanation. The view model's [FailureAlertGate] has already dropped
 * transient failures and repeats; [operation] titles the server failures.
 */
@Composable
fun FailureAlert(error: MultiSetError?, @StringRes operation: Int, onDismiss: () -> Unit) {
    error ?: return
    val (icon, tint) = when (error.kind) {
        MultiSetError.Kind.AUTH -> R.drawable.ic_lock to Palette.Accent
        MultiSetError.Kind.NETWORK -> R.drawable.ic_wifi_off to Palette.TextSecondary
        else -> R.drawable.ic_error_circle to Palette.Danger
    }
    PanelAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.error_dismiss), color = Palette.Accent) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(icon), contentDescription = null, tint = tint)
                Text(title(error, operation))
            }
        },
        text = { Text(body(error)) },
    )
}

@Composable
private fun title(error: MultiSetError, @StringRes operation: Int): String = when (error.kind) {
    MultiSetError.Kind.AUTH -> stringResource(R.string.error_title_auth)
    MultiSetError.Kind.NETWORK -> stringResource(R.string.error_title_network)
    else -> {
        val name = stringResource(operation)
        error.statusCode?.let { stringResource(R.string.error_title_with_code, name, it) } ?: name
    }
}

@Composable
private fun body(error: MultiSetError): String = when (error.kind) {
    MultiSetError.Kind.AUTH -> stringResource(R.string.error_body_auth, error.statusCode ?: 401)
    MultiSetError.Kind.NETWORK -> stringResource(R.string.error_body_network)
    else -> error.message
}
