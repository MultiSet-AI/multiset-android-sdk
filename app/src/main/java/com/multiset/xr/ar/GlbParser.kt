/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.multiset.sdk.math.PoseMath
import com.multiset.sdk.model.Quat
import com.multiset.sdk.model.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** A GLB that cannot be read; the message names what was wrong with it. */
class GlbParseException(message: String) : Exception(message)

/** A node's local transform. A node written as a matrix is decomposed into these three. */
data class GlbTransform(val translation: Vec3 = ZERO, val rotation: Quat = Quat.IDENTITY, val scale: Vec3 = ONE) {

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val ONE = Vec3(1f, 1f, 1f)
        val IDENTITY = GlbTransform()

        /** Decomposes a column-major rigid-plus-scale matrix. */
        fun fromColumnMajor(m: FloatArray): GlbTransform {
            val sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
            val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
            val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
            fun unit(value: Float, length: Float) = if (length > 0f) value / length else value
            val rows = arrayOf(
                floatArrayOf(unit(m[0], sx), unit(m[4], sy), unit(m[8], sz), 0f),
                floatArrayOf(unit(m[1], sx), unit(m[5], sy), unit(m[9], sz), 0f),
                floatArrayOf(unit(m[2], sx), unit(m[6], sy), unit(m[10], sz), 0f),
                floatArrayOf(0f, 0f, 0f, 1f),
            )
            return GlbTransform(Vec3(m[12], m[13], m[14]), PoseMath.extractRotation(rows).normalized(), Vec3(sx, sy, sz))
        }
    }
}

/**
 * One triangle primitive: flat `xyz` [positions], [normals] only when the file carries one per
 * position, and [indices] (implicit `0…n−1` when the file has none). `mode` is ignored.
 */
class GlbPrimitive(val positions: FloatArray, val normals: FloatArray?, val indices: IntArray) {
    val vertexCount: Int get() = positions.size / 3
}

class GlbNode(val transform: GlbTransform, val primitives: List<GlbPrimitive>, val children: List<GlbNode>)

/** The scene's root nodes, or one identity root per mesh when the file has no scene graph. */
class GlbScene(val roots: List<GlbNode>)

/**
 * Port of iOS `MeshRenderer.parse` and `GLTFAccessor.elementLayout`.
 *
 * Positions, normals, indices and the node tree of a plain (non-Draco) GLB, bounds-checked so a
 * malformed file is refused rather than read past its buffer. Reads are unaligned: some exporters
 * leave buffer views unpadded. Safe off the main thread.
 */
object GlbParser {

    private const val MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private const val HEADER_BYTES = 12
    private const val VEC3_BYTES = 12

    private val gson = Gson()

    @Throws(GlbParseException::class)
    fun parse(data: ByteArray): GlbScene {
        val (jsonChunk, binary) = chunks(data)
        val gltf = try {
            gson.fromJson(jsonChunk.toString(Charsets.UTF_8), GltfRoot::class.java)
        } catch (e: JsonParseException) {
            throw GlbParseException("Unreadable glTF JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw GlbParseException("Unreadable glTF JSON: ${e.message}")
        } ?: throw GlbParseException("Empty glTF JSON")
        val meshes = gltf.meshes
        if (meshes.isNullOrEmpty()) throw GlbParseException("No meshes")
        val reader = Reader(gltf, ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN), binary.size)

        val nodes = gltf.nodes
        val rootIndices = gltf.scene?.let { gltf.scenes?.getOrNull(it) }?.nodes
        if (nodes != null && rootIndices != null) {
            return GlbScene(rootIndices.map { reader.node(it, nodes, meshes, depth = 0) })
        }
        return GlbScene(meshes.map { GlbNode(GlbTransform.IDENTITY, reader.primitives(it), emptyList()) })
    }

    private fun chunks(data: ByteArray): Pair<ByteArray, ByteArray> {
        if (data.size < HEADER_BYTES) throw GlbParseException("Invalid header")
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != MAGIC) throw GlbParseException("Invalid magic")
        var offset = HEADER_BYTES
        var jsonChunk: ByteArray? = null
        var binary: ByteArray? = null
        while (offset + 8 <= data.size) {
            val length = buffer.getInt(offset).toLong() and 0xFFFF_FFFFL
            val type = buffer.getInt(offset + 4)
            offset += 8
            if (offset + length > data.size) break
            val chunk = data.copyOfRange(offset, offset + length.toInt())
            when (type) {
                CHUNK_JSON -> jsonChunk = chunk
                CHUNK_BIN -> binary = chunk
            }
            offset += length.toInt()
        }
        if (jsonChunk == null || binary == null) throw GlbParseException("Missing chunks")
        return jsonChunk to binary
    }

    private class Reader(private val gltf: GltfRoot, private val bin: ByteBuffer, private val binLength: Int) {

        fun node(index: Int, nodes: List<GltfNode>, meshes: List<GltfMesh>, depth: Int): GlbNode {
            if (index !in nodes.indices) throw GlbParseException("Node $index out of range")
            if (depth > nodes.size) throw GlbParseException("Node graph has a cycle")
            val node = nodes[index]
            val matrix = node.matrix
            val transform = if (matrix != null && matrix.size == 16) {
                GlbTransform.fromColumnMajor(matrix.toFloatArray())
            } else {
                GlbTransform(
                    translation = node.translation?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) } ?: GlbTransform.ZERO,
                    rotation = node.rotation?.takeIf { it.size == 4 }?.let { Quat(it[0], it[1], it[2], it[3]) } ?: Quat.IDENTITY,
                    scale = node.scale?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) } ?: GlbTransform.ONE,
                )
            }
            val primitives = node.mesh?.takeIf { it in meshes.indices }?.let { primitives(meshes[it]) }.orEmpty()
            val children = node.children.orEmpty().filter { it in nodes.indices }.map { node(it, nodes, meshes, depth + 1) }
            return GlbNode(transform, primitives, children)
        }

        fun primitives(mesh: GltfMesh): List<GlbPrimitive> {
            val accessors = gltf.accessors ?: return emptyList()
            val views = gltf.bufferViews.orEmpty()
            return mesh.primitives.orEmpty().mapNotNull { primitive ->
                val attributes = primitive.attributes.orEmpty()
                val positionIndex = attributes["POSITION"]?.takeIf { it in accessors.indices } ?: return@mapNotNull null
                val positions = vec3(accessors[positionIndex], views)
                if (positions.isEmpty()) return@mapNotNull null
                val normals = attributes["NORMAL"]?.takeIf { it in accessors.indices }?.let { vec3(accessors[it], views) }
                val indices = primitive.indices?.takeIf { it in accessors.indices }?.let { indices(accessors[it], views) }
                    ?: IntArray(positions.size / 3) { it }
                GlbPrimitive(positions, normals?.takeIf { it.size == positions.size }, indices)
            }
        }

        private fun vec3(accessor: GltfAccessor, views: List<GltfBufferView>): FloatArray {
            val (start, stride) = layout(accessor, VEC3_BYTES, views)
            val out = FloatArray(accessor.count * 3)
            for (i in 0 until accessor.count) {
                val offset = start + i * stride
                out[i * 3] = bin.getFloat(offset)
                out[i * 3 + 1] = bin.getFloat(offset + 4)
                out[i * 3 + 2] = bin.getFloat(offset + 8)
            }
            return out
        }

        private fun indices(accessor: GltfAccessor, views: List<GltfBufferView>): IntArray {
            val size = when (accessor.componentType) {
                5121 -> 1
                5123 -> 2
                5125 -> 4
                else -> throw GlbParseException("Unsupported index type ${accessor.componentType}")
            }
            val (start, stride) = layout(accessor, size, views)
            return IntArray(accessor.count) { i ->
                val offset = start + i * stride
                when (size) {
                    1 -> bin.get(offset).toInt() and 0xFF
                    2 -> bin.getShort(offset).toInt() and 0xFFFF
                    else -> bin.getInt(offset)
                }
            }
        }

        /** iOS `GLTFAccessor.elementLayout`: every element inside its view, the view inside the chunk. */
        private fun layout(accessor: GltfAccessor, elementSize: Int, views: List<GltfBufferView>): Pair<Int, Int> {
            val viewIndex = accessor.bufferView?.takeIf { it in views.indices } ?: throw GlbParseException("Invalid buffer view")
            val view = views[viewIndex]
            val viewStart = view.byteOffset ?: 0
            val viewFits = viewStart in 0..binLength && view.byteLength in 0..(binLength - viewStart)
            if (!viewFits) throw GlbParseException("Buffer view overruns the binary chunk")
            val offset = accessor.byteOffset ?: 0
            val stride = view.byteStride ?: elementSize
            if (accessor.count < 0 || offset < 0 || stride < elementSize) throw GlbParseException("Invalid accessor")
            if (accessor.count > 0) {
                // Divided rather than multiplied: a count from the file could overflow the product.
                val room = view.byteLength - offset - elementSize
                if (room < 0 || accessor.count - 1 > room / stride) throw GlbParseException("Accessor overruns its buffer view")
            }
            return (viewStart + offset) to stride
        }
    }
}

// Every field defaults so Kotlin emits the no-arg constructor Gson needs to keep those defaults.
private data class GltfRoot(
    val scene: Int? = null,
    val scenes: List<GltfScene>? = null,
    val nodes: List<GltfNode>? = null,
    val meshes: List<GltfMesh>? = null,
    val accessors: List<GltfAccessor>? = null,
    val bufferViews: List<GltfBufferView>? = null,
)

private data class GltfScene(val nodes: List<Int>? = null)

private data class GltfNode(
    val mesh: Int? = null,
    val children: List<Int>? = null,
    val translation: List<Float>? = null,
    val rotation: List<Float>? = null,
    val scale: List<Float>? = null,
    val matrix: List<Float>? = null,
)

private data class GltfMesh(val primitives: List<GltfPrimitive>? = null)

private data class GltfPrimitive(val attributes: Map<String, Int>? = null, val indices: Int? = null)

private data class GltfAccessor(
    val bufferView: Int? = null,
    val byteOffset: Int? = null,
    val componentType: Int = 0,
    val count: Int = 0,
)

private data class GltfBufferView(
    val byteOffset: Int? = null,
    val byteLength: Int = 0,
    val byteStride: Int? = null,
)
