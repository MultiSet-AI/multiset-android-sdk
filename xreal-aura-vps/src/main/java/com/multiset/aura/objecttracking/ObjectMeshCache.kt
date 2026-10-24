/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.objecttracking

import java.io.File

/**
 * Object meshes kept on the device by object code, so each downloads once; the SDK's repository
 * downloads every time and keeps nothing. Holds the [keep] most recently used meshes. Synchronized
 * because each object's mesh is fetched by its own coroutine.
 */
class ObjectMeshCache(private val directory: File, private val keep: Int = DEFAULT_KEEP) {

    @Synchronized
    fun read(code: String): ByteArray? {
        val file = fileFor(code)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        file.setLastModified(System.currentTimeMillis())
        return bytes
    }

    /** Written beside the target and renamed, so a cut-short write is never read back. */
    @Synchronized
    fun write(code: String, glb: ByteArray) {
        directory.mkdirs()
        val target = fileFor(code)
        val partial = File(directory, target.name + PARTIAL)
        partial.writeBytes(glb)
        if (!partial.renameTo(target)) {
            partial.delete()
            error("Could not store ${target.name}")
        }
        prune()
    }

    @Synchronized
    fun remove(code: String) {
        fileFor(code).delete()
    }

    private fun prune() {
        val meshes = directory.listFiles { file -> file.name.endsWith(GLB) }.orEmpty().sortedByDescending { it.lastModified() }
        meshes.drop(keep).forEach { it.delete() }
    }

    private fun fileFor(code: String) = File(directory, code.replace(Regex("[^A-Za-z0-9_-]"), "-") + GLB)

    companion object {
        const val DEFAULT_KEEP = 8
        private const val GLB = ".glb"
        private const val PARTIAL = ".tmp"
    }
}
