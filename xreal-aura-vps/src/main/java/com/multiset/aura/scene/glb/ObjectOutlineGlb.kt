/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene.glb

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bakes an object mesh into :app's outline look as one GLB, with no custom materials. Primitive 0 is the
 * occluder: the original triangles in opaque black, which writes depth and hides the shell's inside.
 * Primitive 1 is the shell: the same vertices pushed out along smooth normals, with reversed winding so
 * only its far side faces the camera, in the outline colour. Every mesh node of the source is merged
 * into that pair with its transform applied, so the output is a single untransformed node.
 */
object ObjectOutlineGlb {

    /** How far the shell stands off the surface, in metres. */
    const val DEFAULT_WIDTH_M = 0.006f

    val OUTLINE_COLOR = floatArrayOf(0.6f, 0.3f, 1.0f, 1f)

    private const val FLOAT = 5126
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
    private const val UNLIT = "KHR_materials_unlit"
    private val OCCLUDER_COLOR = floatArrayOf(0f, 0f, 0f, 1f)

    /** Throws [IllegalArgumentException] for a [source] GLB that can't be read or has no triangles. */
    fun build(source: ByteArray, widthM: Float = DEFAULT_WIDTH_M, color: FloatArray = OUTLINE_COLOR): ByteArray {
        val (positions, indices) = mergedTriangles(GlbReader(source))
        val normals = OutlineNormals.smoothNormals(positions, indices)
        val shellPositions = FloatArray(positions.size) { positions[it] + normals[it] * widthM }
        val shellIndices = indices.copyOf().also {
            for (t in 0 until it.size step 3) {
                it[t + 1] = indices[t + 2]
                it[t + 2] = indices[t + 1]
            }
        }
        return write(positions, indices, shellPositions, shellIndices, color)
    }

    private fun mergedTriangles(gltf: GlbReader): Pair<FloatArray, IntArray> {
        val positionParts = ArrayList<FloatArray>()
        val indexParts = ArrayList<IntArray>()
        var vertexCount = 0
        for ((mesh, world) in gltf.meshNodes()) {
            for (primitive in mesh.getAsJsonArray("primitives").map { it.asJsonObject }) {
                if ((primitive["mode"]?.asInt ?: GlbReader.TRIANGLES) != GlbReader.TRIANGLES) continue
                val local = gltf.floats(primitive.getAsJsonObject("attributes")["POSITION"].asInt)
                val positions = FloatArray(local.size)
                for (v in 0 until local.size / 3) GltfMatrix.transformPoint(world, local, v * 3, positions)
                val source = primitive["indices"]?.let { gltf.ints(it.asInt) } ?: IntArray(local.size / 3) { it }
                positionParts += positions
                indexParts += IntArray(source.size / 3 * 3) { source[it] + vertexCount }
                vertexCount += local.size / 3
            }
        }
        require(indexParts.any { it.isNotEmpty() }) { "The object mesh has no triangles." }
        return joined(positionParts) to joined(indexParts)
    }

    private fun joined(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun joined(parts: List<IntArray>): IntArray {
        val out = IntArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun write(
        positions: FloatArray,
        indices: IntArray,
        shellPositions: FloatArray,
        shellIndices: IntArray,
        color: FloatArray,
    ): ByteArray {
        val wide = positions.size / 3 > 0xFFFF
        val bin = ByteArrayOutputStream()
        val views = JsonArray()
        val accessors = JsonArray()

        fun view(bytes: ByteArray, target: Int): Int {
            views.add(JsonObject().apply {
                addProperty("buffer", 0)
                addProperty("byteOffset", bin.size())
                addProperty("byteLength", bytes.size)
                addProperty("target", target)
            })
            bin.write(bytes)
            while (bin.size() % 4 != 0) bin.write(0)
            return views.size() - 1
        }

        fun positionAccessor(values: FloatArray): Int {
            val lo = FloatArray(3) { axis -> (axis until values.size step 3).minOf { values[it] } }
            val hi = FloatArray(3) { axis -> (axis until values.size step 3).maxOf { values[it] } }
            accessors.add(JsonObject().apply {
                addProperty("bufferView", view(floatBytes(values), ARRAY_BUFFER))
                addProperty("componentType", FLOAT)
                addProperty("count", values.size / 3)
                addProperty("type", "VEC3")
                add("min", floatArray(lo))
                add("max", floatArray(hi))
            })
            return accessors.size() - 1
        }

        fun indexAccessor(values: IntArray): Int {
            accessors.add(JsonObject().apply {
                addProperty("bufferView", view(indexBytes(values, wide), ELEMENT_ARRAY_BUFFER))
                addProperty("componentType", if (wide) UNSIGNED_INT else UNSIGNED_SHORT)
                addProperty("count", values.size)
                addProperty("type", "SCALAR")
            })
            return accessors.size() - 1
        }

        fun primitive(position: Int, index: Int, material: Int) = JsonObject().apply {
            add("attributes", JsonObject().apply { addProperty("POSITION", position) })
            addProperty("indices", index)
            addProperty("material", material)
        }

        val occluder = primitive(positionAccessor(positions), indexAccessor(indices), 0)
        val shell = primitive(positionAccessor(shellPositions), indexAccessor(shellIndices), 1)

        val json = JsonObject().apply {
            add("asset", JsonObject().apply {
                addProperty("version", "2.0")
                addProperty("generator", "MultiSet-XR object outline")
            })
            add("extensionsUsed", JsonArray().apply { add(UNLIT) })
            addProperty("scene", 0)
            add("scenes", JsonArray().apply {
                add(JsonObject().apply { add("nodes", JsonArray().apply { add(0) }) })
            })
            add("nodes", JsonArray().apply { add(JsonObject().apply { addProperty("mesh", 0) }) })
            add("meshes", JsonArray().apply {
                add(JsonObject().apply { add("primitives", JsonArray().apply { add(occluder); add(shell) }) })
            })
            add("materials", JsonArray().apply {
                add(material("OutlineOccluder", OCCLUDER_COLOR))
                add(material("OutlineShell", color))
            })
            add("accessors", accessors)
            add("bufferViews", views)
            add("buffers", JsonArray().apply { add(JsonObject().apply { addProperty("byteLength", bin.size()) }) })
        }
        return GlbContainer.pack(json.toString().toByteArray(Charsets.UTF_8), bin.toByteArray())
    }

    private fun material(name: String, baseColor: FloatArray) = JsonObject().apply {
        addProperty("name", name)
        add("pbrMetallicRoughness", JsonObject().apply { add("baseColorFactor", floatArray(baseColor)) })
        addProperty("alphaMode", "OPAQUE")
        addProperty("doubleSided", false)
        add("extensions", JsonObject().apply { add(UNLIT, JsonObject()) })
    }

    private fun floatArray(values: FloatArray) = JsonArray().apply { values.forEach(::add) }

    private fun floatBytes(values: FloatArray): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .also { it.asFloatBuffer().put(values) }.array()

    private fun indexBytes(values: IntArray, wide: Boolean): ByteArray {
        val bytes = ByteBuffer.allocate(values.size * if (wide) 4 else 2).order(ByteOrder.LITTLE_ENDIAN)
        if (wide) bytes.asIntBuffer().put(values) else bytes.asShortBuffer().put(ShortArray(values.size) { values[it].toShort() })
        return bytes.array()
    }
}
