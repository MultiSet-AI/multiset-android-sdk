/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene.glb

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a binary glTF 2.0 file whose data is all in its own BIN chunk. Anything it can't read, such as
 * a required extension or an external buffer, throws [IllegalArgumentException] with a readable message.
 */
internal class GlbReader(private val data: ByteArray) {
    private val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val json: JsonObject
    private val binStart: Int

    init {
        require(data.size >= 20 && buffer.getInt(0) == GLB_MAGIC && buffer.getInt(4) == 2) { "Not a GLB 2.0 file." }
        val jsonLength = buffer.getInt(12)
        require(jsonLength in 1..data.size - 20) { "The GLB's JSON chunk is cut short." }
        json = JsonParser.parseString(String(data, 20, jsonLength, Charsets.UTF_8)).asJsonObject
        binStart = 20 + jsonLength + 8
        json.getAsJsonArray("extensionsRequired")?.let { required ->
            throw IllegalArgumentException("GLB files needing ${required.joinToString { it.asString }} are not supported.")
        }
    }

    /** Every node with a mesh in the default scene, with its transform into the file's root frame. */
    fun meshNodes(): List<Pair<JsonObject, FloatArray>> {
        val nodes = json.getAsJsonArray("nodes") ?: return emptyList()
        val meshes = json.getAsJsonArray("meshes")
        val roots = json.getAsJsonArray("scenes")?.get(json["scene"]?.asInt ?: 0)?.asJsonObject
            ?.getAsJsonArray("nodes")?.map { it.asInt }
            ?: (0 until nodes.size()).toList()
        val found = ArrayList<Pair<JsonObject, FloatArray>>()
        fun visit(index: Int, parent: FloatArray) {
            val node = nodes[index].asJsonObject
            val world = GltfMatrix.multiply(parent, GltfMatrix.local(node))
            node["mesh"]?.let { found += meshes[it.asInt].asJsonObject to world }
            node.getAsJsonArray("children")?.forEach { visit(it.asInt, world) }
        }
        roots.forEach { visit(it, GltfMatrix.IDENTITY) }
        return found
    }

    /** An accessor's components as floats: 32-bit floats, or normalized integers mapped to 0..1 or -1..1. */
    fun floats(accessorIndex: Int): FloatArray {
        val accessor = accessor(accessorIndex)
        val components = components(accessor)
        val count = accessor["count"].asInt
        if (!accessor.has("bufferView")) return FloatArray(count * components)
        val type = accessor["componentType"].asInt
        val size = componentSize(type)
        require(type == FLOAT || accessor["normalized"]?.asBoolean == true) {
            "Accessor $accessorIndex holds integers that aren't normalized."
        }
        val (start, stride) = layout(accessor, size * components)
        return FloatArray(count * components) {
            val at = start + (it / components) * stride + (it % components) * size
            when (type) {
                FLOAT -> buffer.getFloat(at)
                UNSIGNED_BYTE -> (buffer.get(at).toInt() and 0xFF) / 255f
                UNSIGNED_SHORT -> (buffer.getShort(at).toInt() and 0xFFFF) / 65535f
                BYTE -> maxOf(buffer.get(at) / 127f, -1f)
                else -> maxOf(buffer.getShort(at) / 32767f, -1f)
            }
        }
    }

    fun ints(accessorIndex: Int): IntArray {
        val accessor = accessor(accessorIndex)
        val type = accessor["componentType"].asInt
        val size = when (type) {
            UNSIGNED_BYTE -> 1
            UNSIGNED_SHORT -> 2
            UNSIGNED_INT -> 4
            else -> throw IllegalArgumentException("Unsupported index type $type.")
        }
        val (start, stride) = layout(accessor, size)
        return IntArray(accessor["count"].asInt) {
            val at = start + it * stride
            when (size) {
                1 -> buffer.get(at).toInt() and 0xFF
                2 -> buffer.getShort(at).toInt() and 0xFFFF
                else -> buffer.getInt(at)
            }
        }
    }

    /** A buffer view's bytes, such as an embedded image. */
    fun bytes(viewIndex: Int): ByteArray {
        val view = view(viewIndex)
        val start = binStart + (view["byteOffset"]?.asInt ?: 0)
        val length = view["byteLength"].asInt
        require(start >= binStart && start + length <= data.size) { "Buffer view $viewIndex runs past the file." }
        return data.copyOfRange(start, start + length)
    }

    private fun accessor(index: Int): JsonObject {
        val accessor = json.getAsJsonArray("accessors")[index].asJsonObject
        require(!accessor.has("sparse")) { "Sparse accessors are not supported." }
        return accessor
    }

    private fun layout(accessor: JsonObject, packedStride: Int): Pair<Int, Int> {
        val view = view(accessor["bufferView"].asInt)
        val start = binStart + (view["byteOffset"]?.asInt ?: 0) + (accessor["byteOffset"]?.asInt ?: 0)
        return start to (view["byteStride"]?.asInt ?: packedStride)
    }

    /** A view into the BIN chunk; data in another buffer would be outside the file. */
    private fun view(index: Int): JsonObject {
        val view = json.getAsJsonArray("bufferViews")[index].asJsonObject
        val buffer = view["buffer"]?.asInt ?: 0
        require(buffer == 0 && json.getAsJsonArray("buffers")?.get(0)?.asJsonObject?.has("uri") != true) {
            "GLB files with external buffers are not supported."
        }
        return view
    }

    private fun components(accessor: JsonObject): Int = when (val type = accessor["type"].asString) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        else -> throw IllegalArgumentException("Unsupported accessor type $type.")
    }

    private fun componentSize(type: Int): Int = when (type) {
        FLOAT, UNSIGNED_INT -> 4
        UNSIGNED_SHORT, SHORT -> 2
        UNSIGNED_BYTE, BYTE -> 1
        else -> throw IllegalArgumentException("Unsupported component type $type.")
    }

    companion object {
        const val FLOAT = 5126
        const val BYTE = 5120
        const val UNSIGNED_BYTE = 5121
        const val SHORT = 5122
        const val UNSIGNED_SHORT = 5123
        const val UNSIGNED_INT = 5125
        const val TRIANGLES = 4
        private const val GLB_MAGIC = 0x46546C67
    }
}

/** Column-major 4x4 matrices, as glTF stores them. */
internal object GltfMatrix {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    fun local(node: JsonObject): FloatArray {
        node.getAsJsonArray("matrix")?.let { m -> return FloatArray(16) { m[it].asFloat } }
        val t = node.getAsJsonArray("translation")?.map { it.asFloat } ?: listOf(0f, 0f, 0f)
        val r = node.getAsJsonArray("rotation")?.map { it.asFloat } ?: listOf(0f, 0f, 0f, 1f)
        val s = node.getAsJsonArray("scale")?.map { it.asFloat } ?: listOf(1f, 1f, 1f)
        val (x, y, z, w) = r
        return floatArrayOf(
            (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0f,
            (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0f,
            (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0f,
            t[0], t[1], t[2], 1f,
        )
    }

    fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16) { i ->
        val column = i / 4
        val row = i % 4
        (0 until 4).sumOf { k -> (a[k * 4 + row] * b[column * 4 + k]).toDouble() }.toFloat()
    }

    fun transformPoint(m: FloatArray, source: FloatArray, at: Int, target: FloatArray) {
        val x = source[at]
        val y = source[at + 1]
        val z = source[at + 2]
        target[at] = m[0] * x + m[4] * y + m[8] * z + m[12]
        target[at + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        target[at + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }
}
