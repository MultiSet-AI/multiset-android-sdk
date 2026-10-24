/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene.glb

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object GlbContainer {

    /** GLB 2.0 container: header, JSON chunk padded with spaces, BIN chunk padded with zeros. */
    fun pack(json: ByteArray, bin: ByteArray): ByteArray {
        val jsonPadded = (json.size + 3) and 3.inv()
        val binPadded = (bin.size + 3) and 3.inv()
        val total = 12 + 8 + jsonPadded + 8 + binPadded
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67)
            putInt(2)
            putInt(total)
            putInt(jsonPadded)
            putInt(0x4E4F534A)
            put(json)
            repeat(jsonPadded - json.size) { put(' '.code.toByte()) }
            putInt(binPadded)
            putInt(0x004E4942)
            put(bin)
        }.array()
    }
}
