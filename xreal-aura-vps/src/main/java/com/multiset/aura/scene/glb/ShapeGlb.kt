/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene.glb

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Simple unlit shapes, and their GLB for SceneCore. A colour with alpha below 1 is drawn see-through
 * (`alphaMode: BLEND`), the way the map mesh's texture alpha is; entity alpha is never needed.
 */
object ShapeGlb {

    private const val FLOAT = 5126
    private const val UNSIGNED_SHORT = 5123
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
    private const val UNLIT = "KHR_materials_unlit"

    // Corners 0..7 are (x0|x1, y0|y1, z0|z1) as listed in box; two triangles per face.
    private val BOX_INDICES = shortArrayOf(
        0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4,
        3, 7, 6, 3, 6, 2, 0, 4, 7, 0, 7, 3, 1, 2, 6, 1, 6, 5,
    )

    private const val SPHERE_SEGMENTS = 16
    private const val SPHERE_RINGS = 8
    private const val RING_SEGMENTS = 48

    fun box(min: FloatArray, max: FloatArray, color: FloatArray) = GizmoShape(
        floatArrayOf(
            min[0], min[1], min[2], max[0], min[1], min[2], max[0], max[1], min[2], min[0], max[1], min[2],
            min[0], min[1], max[2], max[0], min[1], max[2], max[0], max[1], max[2], min[0], max[1], max[2],
        ),
        BOX_INDICES,
        color,
    )

    /** A UV sphere; the ring and segment counts are multiples of 4, so it touches +-radius on every axis. */
    fun sphere(radius: Float, color: FloatArray, centre: FloatArray = floatArrayOf(0f, 0f, 0f)): GizmoShape {
        val positions = ArrayList<Float>()
        for (ring in 0..SPHERE_RINGS) {
            val polar = PI * ring / SPHERE_RINGS
            for (segment in 0..SPHERE_SEGMENTS) {
                val azimuth = 2 * PI * segment / SPHERE_SEGMENTS
                positions += centre[0] + (radius * sin(polar) * cos(azimuth)).toFloat()
                positions += centre[1] + (radius * cos(polar)).toFloat()
                positions += centre[2] + (radius * sin(polar) * sin(azimuth)).toFloat()
            }
        }
        val indices = ArrayList<Short>()
        val stride = SPHERE_SEGMENTS + 1
        for (ring in 0 until SPHERE_RINGS) {
            for (segment in 0 until SPHERE_SEGMENTS) {
                val a = ring * stride + segment
                val b = a + stride
                indices += listOf(a, b, a + 1, a + 1, b, b + 1).map { it.toShort() }
            }
        }
        return GizmoShape(positions.toFloatArray(), indices.toShortArray(), color)
    }

    /** A flat annulus in the plane y = [y], centred on the Y axis. */
    fun ring(inner: Float, outer: Float, y: Float, color: FloatArray): GizmoShape {
        val positions = FloatArray((RING_SEGMENTS + 1) * 2 * 3)
        for (segment in 0..RING_SEGMENTS) {
            val angle = 2 * PI * segment / RING_SEGMENTS
            for ((k, radius) in listOf(inner, outer).withIndex()) {
                val at = (segment * 2 + k) * 3
                positions[at] = (radius * cos(angle)).toFloat()
                positions[at + 1] = y
                positions[at + 2] = (radius * sin(angle)).toFloat()
            }
        }
        val indices = ShortArray(RING_SEGMENTS * 6)
        for (segment in 0 until RING_SEGMENTS) {
            val a = segment * 2
            listOf(a, a + 2, a + 1, a + 1, a + 2, a + 3).forEachIndexed { i, v -> indices[segment * 6 + i] = v.toShort() }
        }
        return GizmoShape(positions, indices, color)
    }

    /** One node, mesh and two-sided unlit material per shape, in order. */
    fun write(shapes: List<GizmoShape>): ByteArray {
        val bin = ByteBuffer.allocate(shapes.sumOf { (it.positions.size * 4 + it.indices.size * 2).alignTo4() })
            .order(ByteOrder.LITTLE_ENDIAN)
        val bufferViews = JsonArray()
        val accessors = JsonArray()
        val meshes = JsonArray()
        val materials = JsonArray()
        val nodes = JsonArray()

        shapes.forEachIndexed { i, shape ->
            val positionsOffset = bin.position()
            shape.positions.forEach { bin.putFloat(it) }
            val indicesOffset = bin.position()
            shape.indices.forEach { bin.putShort(it) }
            while (bin.position() % 4 != 0) bin.put(0)

            val vertexCount = shape.positions.size / 3
            val axisValues = { axis: Int -> (0 until vertexCount).map { shape.positions[it * 3 + axis] } }
            bufferViews.add(bufferView(positionsOffset, shape.positions.size * 4, ARRAY_BUFFER))
            bufferViews.add(bufferView(indicesOffset, shape.indices.size * 2, ELEMENT_ARRAY_BUFFER))
            accessors.add(
                JsonObject().apply {
                    addProperty("bufferView", 2 * i)
                    addProperty("componentType", FLOAT)
                    addProperty("count", vertexCount)
                    addProperty("type", "VEC3")
                    add("min", floats(*FloatArray(3) { axisValues(it).min() }))
                    add("max", floats(*FloatArray(3) { axisValues(it).max() }))
                }
            )
            accessors.add(
                JsonObject().apply {
                    addProperty("bufferView", 2 * i + 1)
                    addProperty("componentType", UNSIGNED_SHORT)
                    addProperty("count", shape.indices.size)
                    addProperty("type", "SCALAR")
                }
            )
            meshes.add(
                JsonObject().apply {
                    add("primitives", JsonArray().apply {
                        add(JsonObject().apply {
                            add("attributes", JsonObject().apply { addProperty("POSITION", 2 * i) })
                            addProperty("indices", 2 * i + 1)
                            addProperty("material", i)
                        })
                    })
                }
            )
            materials.add(
                JsonObject().apply {
                    add("pbrMetallicRoughness", JsonObject().apply { add("baseColorFactor", floats(*shape.color)) })
                    if (shape.color[3] < 1f) addProperty("alphaMode", "BLEND")
                    addProperty("doubleSided", true)
                    add("extensions", JsonObject().apply { add(UNLIT, JsonObject()) })
                }
            )
            nodes.add(JsonObject().apply { addProperty("mesh", i) })
        }

        val json = JsonObject().apply {
            add("asset", JsonObject().apply { addProperty("version", "2.0") })
            add("extensionsUsed", JsonArray().apply { add(UNLIT) })
            addProperty("scene", 0)
            add("scenes", JsonArray().apply {
                add(JsonObject().apply { add("nodes", JsonArray().apply { shapes.indices.forEach(::add) }) })
            })
            add("nodes", nodes)
            add("meshes", meshes)
            add("materials", materials)
            add("accessors", accessors)
            add("bufferViews", bufferViews)
            add("buffers", JsonArray().apply {
                add(JsonObject().apply { addProperty("byteLength", bin.capacity()) })
            })
        }
        return GlbContainer.pack(json.toString().toByteArray(Charsets.UTF_8), bin.array())
    }

    private fun bufferView(offset: Int, length: Int, target: Int) = JsonObject().apply {
        addProperty("buffer", 0)
        addProperty("byteOffset", offset)
        addProperty("byteLength", length)
        addProperty("target", target)
    }

    private fun floats(vararg values: Float) = JsonArray().apply { values.forEach(::add) }

    private fun Int.alignTo4() = (this + 3) and 3.inv()
}
