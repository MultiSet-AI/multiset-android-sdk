/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.camera

sealed interface CameraStatus {
    data object Off : CameraStatus
    data object Opening : CameraStatus
    data object Live : CameraStatus
    data class Error(val message: String) : CameraStatus
}
