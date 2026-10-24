/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.localization

import com.google.gson.Gson
import com.multiset.sdk.mesh.MapMeshResult
import java.io.File

/**
 * Map meshes kept on the device, so each map's mesh downloads once (12.9 MB for the AES map) and
 * later sessions read it from storage. The SDK's repository downloads every time and keeps nothing.
 * A map re-meshed on the server keeps its old mesh here until the entry is [remove]d or the app's
 * storage is cleared. Holds the [keep] most recently used meshes.
 */
class MapMeshCache(private val directory: File, private val keep: Int = DEFAULT_KEEP) {

    /** A map's mesh; a map set places its member's mesh with the set's pose, so it is part of the key. */
    data class Key(val mapCode: String, val mapSetCode: String? = null) {
        internal val fileName: String = listOf(mapSetCode ?: "map", mapCode).joinToString("_") { it.safe() }

        private fun String.safe() = replace(Regex("[^A-Za-z0-9_-]"), "-")
    }

    /** [rightHanded] is absent from entries saved before SDK 2.1.0, which gave a set member's pose left-handed. */
    private class Entry(
        val mapId: String?,
        val localPosition: FloatArray?,
        val localRotation: FloatArray?,
        val rightHanded: Boolean? = true,
    )

    private val gson = Gson()

    /** The cached mesh, or null; an unreadable or truncated entry is removed. */
    fun read(key: Key): MapMeshResult? {
        val glb = glbFile(key)
        val entry = entryFile(key)
        if (!glb.isFile || !entry.isFile) return null
        val mesh = runCatching {
            val saved = gson.fromJson(entry.readText(), Entry::class.java)
            val bytes = glb.readBytes()
            val position = saved.localPosition?.takeIf { it.size == 3 }
            val rotation = saved.localRotation?.takeIf { it.size == 4 }
            val misplaced = key.mapSetCode != null && saved.rightHanded != true
            if (saved.mapId == null || position == null || rotation == null || misplaced || !isGlb(bytes)) null
            else MapMeshResult(saved.mapId, bytes, position, rotation)
        }.getOrNull()
        if (mesh == null) remove(key) else glb.setLastModified(System.currentTimeMillis())
        return mesh
    }

    /** Stores [mesh]; the entry file goes last, so a cut-short write is never read back. */
    fun write(key: Key, mesh: MapMeshResult) {
        directory.mkdirs()
        writeAtomically(glbFile(key), mesh.meshBytes)
        val entry = Entry(mesh.mapId, mesh.localPosition, mesh.localRotation)
        writeAtomically(entryFile(key), gson.toJson(entry).toByteArray())
        prune()
    }

    fun remove(key: Key) {
        glbFile(key).delete()
        entryFile(key).delete()
    }

    private fun prune() {
        val meshes = directory.listFiles { file -> file.name.endsWith(GLB) }.orEmpty().sortedByDescending { it.lastModified() }
        for (stale in meshes.drop(keep)) {
            stale.delete()
            File(directory, stale.name.removeSuffix(GLB) + ENTRY).delete()
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val partial = File(directory, target.name + ".part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(target)) {
            partial.delete()
            error("Could not store ${target.name}")
        }
    }

    private fun glbFile(key: Key) = File(directory, key.fileName + GLB)

    private fun entryFile(key: Key) = File(directory, key.fileName + ENTRY)

    companion object {
        const val DEFAULT_KEEP = 4
        private const val GLB = ".glb"
        private const val ENTRY = ".json"
        private const val GLB_MAGIC = 0x46546C67

        /** A binary glTF whose header declares exactly its length. */
        fun isGlb(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            fun int(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
            return int(0) == GLB_MAGIC && int(8) == bytes.size
        }
    }
}
