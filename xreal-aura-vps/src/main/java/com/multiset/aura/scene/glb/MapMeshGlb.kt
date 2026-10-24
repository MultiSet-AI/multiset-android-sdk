/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene.glb

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.multiset.sdk.model.Vec3
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned bounds of one mesh chunk, in map space. */
data class ChunkBounds(val min: Vec3, val max: Vec3)

/**
 * A map mesh ready for SceneCore: the rewritten GLB, and each chunk's bounds and [revealDistances]
 * (where its shell starts), in the same node order. Shells are [revealBand] deep; 0 without a reveal.
 */
class PreparedMapMesh(
    val glb: ByteArray,
    val chunks: List<ChunkBounds>,
    val revealDistances: List<Float>,
    val revealBand: Float,
)

/** One chunk in map space: xyz [positions], grid [uvs] in metres, and triangle [indices]. */
class MapMeshChunk(val positions: FloatArray, val uvs: FloatArray, val indices: IntArray, val bounds: ChunkBounds)

/** The gridded map mesh as plain arrays, for renderers that don't load glTF. */
class MapMeshGeometry(val chunks: List<MapMeshChunk>)

/**
 * Gives a MultiSet map mesh the sample's look without custom shaders. The sample's `radial_reveal.mat`
 * draws a 1 m yellow grid over see-through purple, choosing the grid plane per face from its normal.
 * Here each triangle gets texture coordinates from the same plane, and a texture carries the same
 * grid. Vertices land in map space. Each source mesh node becomes one chunk (node `chunk_i`); with a
 * reveal centre, the chunks are instead shells around it, by distance along the floor, so the sample's
 * radial reveal can be played by showing them in order. Each shell then also gets one twin per glow level (`glow_k_i`) drawing
 * the same triangles with that much of the shader's glow baked in, so nothing swaps materials at runtime.
 */
object MapMeshGlb {

    private const val FLOAT = 5126
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val TRIANGLES = 4
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
    private const val UNLIT = "KHR_materials_unlit"

    /** Depth of the shells the mesh is cut into for the reveal, in metres: the front's step. */
    const val REVEAL_BAND_M = 0.1f

    /** Every shell is a node per glow level, so large maps get deeper shells rather than more. */
    const val MAX_SHELLS = 128

    /**
     * [revealCentre] is in map space; without it every chunk shows at once (reveal distance 0). With a
     * [viewpoint] (map space), only the triangles visible from it are kept ([MeshVisibility]).
     */
    fun prepare(
        source: ByteArray,
        palette: GridPalette = GridPalette.SAMPLE,
        revealCentre: Vec3? = null,
        viewpoint: Vec3? = null,
    ): PreparedMapMesh {
        val gltf = GlbReader(source)
        val nodes = gltf.meshNodes().map { (mesh, world) -> triangles(gltf, mesh, world) }
            .let { if (viewpoint == null) it else visibleFrom(it, viewpoint) }
        val chunks: List<Chunk>
        val distances: List<Float>
        var depth = 0f
        if (revealCentre == null) {
            chunks = nodes.mapNotNull { primitives ->
                Chunk().apply { primitives.forEach { (positions, indices) -> addTriangles(positions, indices) } }
                    .takeIf { it.indices.size > 0 }
            }
            distances = chunks.map { 0f }
        } else {
            val primitives = nodes.flatten()
            depth = max(REVEAL_BAND_M, furthestVertex(primitives, revealCentre) / MAX_SHELLS)
            val bands = sortedMapOf<Int, Chunk>()
            for ((positions, indices) in primitives) {
                val byBand = HashMap<Int, IntList>()
                for (t in 0 until indices.size / 3) {
                    val band = (centroidDistance(positions, indices, t, revealCentre) / depth).toInt()
                    val list = byBand.getOrPut(band) { IntList() }
                    for (k in 0 until 3) list += indices[t * 3 + k]
                }
                byBand.forEach { (band, list) -> bands.getOrPut(band) { Chunk() }.addTriangles(positions, list.toArray()) }
            }
            val kept = bands.filterValues { it.indices.size > 0 }
            chunks = kept.values.toList()
            distances = kept.keys.map { it * depth }
        }
        require(chunks.isNotEmpty()) { "The map mesh has no triangles." }
        val bounds = chunks.map { it.bounds() }
        val glowLevels = if (revealCentre == null) 0 else MeshReveal.GLOW_LEVELS
        return PreparedMapMesh(write(chunks, bounds, palette, glowLevels), bounds, distances, depth)
    }

    /** One chunk per source mesh node, with the same vertices and grid coordinates as [prepare]'s GLB. */
    fun geometry(source: ByteArray): MapMeshGeometry {
        val gltf = GlbReader(source)
        val chunks = gltf.meshNodes().mapNotNull { (mesh, world) ->
            Chunk().apply { triangles(gltf, mesh, world).forEach { (positions, indices) -> addTriangles(positions, indices) } }
                .takeIf { it.indices.size > 0 }
        }
        require(chunks.isNotEmpty()) { "The map mesh has no triangles." }
        return MapMeshGeometry(chunks.map { MapMeshChunk(it.positions.toArray(), it.uvs.toArray(), it.indices.toArray(), it.bounds()) })
    }

    private fun visibleFrom(nodes: List<List<Pair<FloatArray, IntArray>>>, eye: Vec3): List<List<Pair<FloatArray, IntArray>>> {
        val flags = MeshVisibility.visible(nodes.flatten(), eye).iterator()
        return nodes.map { primitives ->
            primitives.map { (positions, indices) ->
                val keep = flags.next()
                val kept = IntList()
                for (t in keep.indices) if (keep[t]) for (k in 0 until 3) kept += indices[t * 3 + k]
                positions to kept.toArray()
            }
        }
    }

    private fun furthestVertex(primitives: List<Pair<FloatArray, IntArray>>, centre: Vec3): Float {
        var furthest = 0f
        for ((p, _) in primitives) {
            for (v in 0 until p.size / 3) {
                furthest = max(furthest, MeshReveal.floorDistance(p[v * 3] - centre.x, p[v * 3 + 2] - centre.z))
            }
        }
        return furthest
    }

    /** Each triangle primitive of [mesh], as map-space positions and triangle indices. */
    private fun triangles(gltf: GlbReader, mesh: JsonObject, world: FloatArray): List<Pair<FloatArray, IntArray>> =
        mesh.getAsJsonArray("primitives").map { it.asJsonObject }
            .filter { (it["mode"]?.asInt ?: TRIANGLES) == TRIANGLES }
            .map { primitive ->
                val local = gltf.floats(primitive.getAsJsonObject("attributes")["POSITION"].asInt)
                val positions = FloatArray(local.size)
                for (v in 0 until local.size / 3) GltfMatrix.transformPoint(world, local, v * 3, positions)
                val indices = primitive["indices"]?.let { gltf.ints(it.asInt) } ?: IntArray(positions.size / 3) { it }
                positions to indices
            }

    private fun centroidDistance(p: FloatArray, indices: IntArray, t: Int, centre: Vec3): Float {
        var x = 0f
        var z = 0f
        for (k in 0 until 3) {
            val v = indices[t * 3 + k] * 3
            x += p[v]
            z += p[v + 2]
        }
        return MeshReveal.floorDistance(x / 3 - centre.x, z / 3 - centre.z)
    }

    /** One output mesh: vertices are split where faces with different grid planes meet. */
    private class Chunk {
        val positions = FloatList()
        val uvs = FloatList()
        val indices = IntList()

        fun addTriangles(positions: FloatArray, triangles: IntArray) {
            val remap = HashMap<Long, Int>()
            for (t in 0 until triangles.size / 3) {
                val a = triangles[t * 3]
                val b = triangles[t * 3 + 1]
                val c = triangles[t * 3 + 2]
                val plane = gridPlane(positions, a, b, c) ?: continue
                for (source in intArrayOf(a, b, c)) {
                    indices += remap.getOrPut(source.toLong() * 3 + plane) { addVertex(positions, source, plane) }
                }
            }
        }

        private fun addVertex(source: FloatArray, vertex: Int, plane: Int): Int {
            val x = source[vertex * 3]
            val y = source[vertex * 3 + 1]
            val z = source[vertex * 3 + 2]
            positions += x
            positions += y
            positions += z
            // 1 m grid cells, so texture coordinates are the coordinates in metres.
            when (plane) {
                PLANE_XZ -> { uvs += x; uvs += z }
                PLANE_YZ -> { uvs += y; uvs += z }
                else -> { uvs += x; uvs += y }
            }
            return positions.size / 3 - 1
        }

        fun bounds(): ChunkBounds {
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (i in 0 until positions.size) {
                lo[i % 3] = min(lo[i % 3], positions[i])
                hi[i % 3] = max(hi[i % 3], positions[i])
            }
            return ChunkBounds(Vec3(lo[0], lo[1], lo[2]), Vec3(hi[0], hi[1], hi[2]))
        }
    }

    private const val PLANE_YZ = 0
    private const val PLANE_XZ = 1
    private const val PLANE_XY = 2

    /** The shader's `gridFactor` plane choice; null for a degenerate triangle. */
    private fun gridPlane(p: FloatArray, a: Int, b: Int, c: Int): Int? {
        val ux = p[b * 3] - p[a * 3]
        val uy = p[b * 3 + 1] - p[a * 3 + 1]
        val uz = p[b * 3 + 2] - p[a * 3 + 2]
        val vx = p[c * 3] - p[a * 3]
        val vy = p[c * 3 + 1] - p[a * 3 + 1]
        val vz = p[c * 3 + 2] - p[a * 3 + 2]
        val nx = abs(uy * vz - uz * vy)
        val ny = abs(uz * vx - ux * vz)
        val nz = abs(ux * vy - uy * vx)
        return when {
            nx + ny + nz == 0f -> null
            ny > nx && ny > nz -> PLANE_XZ
            nx > nz -> PLANE_YZ
            else -> PLANE_XY
        }
    }

    /** Nodes are every chunk with material 0, then every chunk again for each glow level 1..[glowLevels]. */
    private fun write(chunks: List<Chunk>, bounds: List<ChunkBounds>, palette: GridPalette, glowLevels: Int): ByteArray {
        val bin = ByteArrayOutputStream()
        val views = JsonArray()
        val accessors = JsonArray()
        val meshes = JsonArray()
        val nodes = JsonArray()

        fun view(bytes: ByteArray, target: Int?): Int {
            while (bin.size() % 4 != 0) bin.write(0)
            views.add(JsonObject().apply {
                addProperty("buffer", 0)
                addProperty("byteOffset", bin.size())
                addProperty("byteLength", bytes.size)
                target?.let { addProperty("target", it) }
            })
            bin.write(bytes)
            return views.size() - 1
        }

        fun accessor(view: Int, componentType: Int, count: Int, type: String, bounds: ChunkBounds? = null): Int {
            accessors.add(JsonObject().apply {
                addProperty("bufferView", view)
                addProperty("componentType", componentType)
                addProperty("count", count)
                addProperty("type", type)
                bounds?.let {
                    add("min", JsonArray().apply { add(it.min.x); add(it.min.y); add(it.min.z) })
                    add("max", JsonArray().apply { add(it.max.x); add(it.max.y); add(it.max.z) })
                }
            })
            return accessors.size() - 1
        }

        class Attributes(val position: Int, val uv: Int, val indices: Int)

        val attributes = chunks.mapIndexed { i, chunk ->
            val vertexCount = chunk.positions.size / 3
            val wide = vertexCount > 0xFFFF
            Attributes(
                position = accessor(view(chunk.positions.toBytes(), ARRAY_BUFFER), FLOAT, vertexCount, "VEC3", bounds[i]),
                uv = accessor(view(chunk.uvs.toBytes(), ARRAY_BUFFER), FLOAT, vertexCount, "VEC2"),
                indices = accessor(
                    view(chunk.indices.toBytes(wide), ELEMENT_ARRAY_BUFFER),
                    if (wide) UNSIGNED_INT else UNSIGNED_SHORT,
                    chunk.indices.size,
                    "SCALAR",
                ),
            )
        }
        // Glow twins share their shell's accessors; only the mesh entry and material differ.
        for (level in 0..glowLevels) {
            attributes.forEachIndexed { i, a ->
                meshes.add(JsonObject().apply {
                    add("primitives", JsonArray().apply {
                        add(JsonObject().apply {
                            add("attributes", JsonObject().apply {
                                addProperty("POSITION", a.position)
                                addProperty("TEXCOORD_0", a.uv)
                            })
                            addProperty("indices", a.indices)
                            addProperty("material", level)
                        })
                    })
                })
                nodes.add(JsonObject().apply {
                    addProperty("name", if (level == 0) "chunk_$i" else "glow_${level}_$i")
                    addProperty("mesh", meshes.size() - 1)
                })
            }
        }
        val images = (0..glowLevels).map { level ->
            val glow = if (level == 0) 0f else level.toFloat() / glowLevels
            view(GridTexture.png(palette = palette, glow = glow), null)
        }

        val json = JsonObject().apply {
            add("asset", JsonObject().apply {
                addProperty("version", "2.0")
                addProperty("generator", "MultiSet-XR map mesh grid")
            })
            add("extensionsUsed", JsonArray().apply { add(UNLIT) })
            addProperty("scene", 0)
            add("scenes", JsonArray().apply {
                add(JsonObject().apply { add("nodes", JsonArray().apply { (0 until nodes.size()).forEach(::add) }) })
            })
            add("nodes", nodes)
            add("meshes", meshes)
            // Glow levels are the grid material with another texture, so they blend exactly as it does.
            add("materials", JsonArray().apply {
                for (level in 0..glowLevels) add(JsonObject().apply {
                    addProperty("name", if (level == 0) "MapGrid" else "RevealGlow$level")
                    add("pbrMetallicRoughness", JsonObject().apply {
                        add("baseColorTexture", JsonObject().apply { addProperty("index", level) })
                        addProperty("metallicFactor", 0)
                        addProperty("roughnessFactor", 1)
                    })
                    addProperty("alphaMode", "BLEND")
                    addProperty("doubleSided", false)
                    add("extensions", JsonObject().apply { add(UNLIT, JsonObject()) })
                })
            })
            add("textures", JsonArray().apply {
                for (level in 0..glowLevels) add(JsonObject().apply {
                    addProperty("sampler", 0)
                    addProperty("source", level)
                })
            })
            add("samplers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("magFilter", 9729)
                    addProperty("minFilter", 9987)
                    addProperty("wrapS", 10497)
                    addProperty("wrapT", 10497)
                })
            })
            add("images", JsonArray().apply {
                images.forEach { image ->
                    add(JsonObject().apply {
                        addProperty("bufferView", image)
                        addProperty("mimeType", "image/png")
                    })
                }
            })
            add("accessors", accessors)
            add("bufferViews", views)
            add("buffers", JsonArray().apply { add(JsonObject().apply { addProperty("byteLength", bin.size()) }) })
        }
        return GlbContainer.pack(json.toString().toByteArray(Charsets.UTF_8), bin.toByteArray())
    }

    private class FloatList {
        private var data = FloatArray(1024)
        var size = 0
            private set

        operator fun plusAssign(value: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        operator fun get(index: Int) = data[index]

        fun toArray(): FloatArray = data.copyOf(size)

        fun toBytes(): ByteArray = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until size) putFloat(data[i])
        }.array()
    }

    private class IntList {
        private var data = IntArray(1024)
        var size = 0
            private set

        operator fun plusAssign(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)

        fun toBytes(wide: Boolean): ByteArray =
            ByteBuffer.allocate(size * if (wide) 4 else 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (i in 0 until size) if (wide) putInt(data[i]) else putShort(data[i].toShort())
            }.array()
    }
}

/**
 * [SAMPLE] is the shader's palette, made for video passthrough, alpha-blended over the camera image.
 *
 * An additive see-through display such as Aura can only add light, so [ADDITIVE]'s fill is brighter.
 * Both are alpha-blended: on the lenses a blended fill adds the same light as an opaque one would, but
 * Aura's recordings composite by alpha, and an opaque mesh hid the room there. The old note that
 * alpha "had no effect" on Aura dates from when entity alpha forced the mesh opaque.
 */
enum class GridPalette(val fill: FloatArray, val fillAlpha: Float, val lineAlpha: Float) {
    SAMPLE(floatArrayOf(0.357f, 0.114f, 0.6f), 0.39f, 0.8f),
    ADDITIVE(floatArrayOf(0.6f, 0.3f, 1f), 0.035f, 0.5f),
}

/**
 * The sample shader's grid, baked into one 1 m cell: a see-through fill with soft 2 cm yellow lines
 * along the cell edges, plus [png]'s `glow` of the reveal front's orange added as the shader adds it.
 * Straight (not premultiplied) alpha, as glTF expects.
 */
object GridTexture {

    private val YELLOW = floatArrayOf(1f, 1f, 0f)
    private val GLOW = floatArrayOf(1f, 0.227f, 0f)
    private const val LINE_WIDTH = 0.02f

    /** Row-major RGBA, top row first, with the same pixels as [png]. */
    fun rgba(size: Int = 256, palette: GridPalette = GridPalette.SAMPLE, glow: Float = 0f): ByteArray {
        val pixels = ByteArray(size * size * 4)
        var at = 0
        for (row in 0 until size) {
            for (column in 0 until size) {
                val u = (column + 0.5f) / size
                val v = (row + 0.5f) / size
                val edge = min(min(u, 1 - u), min(v, 1 - v))
                val grid = 1f - smoothstep(0f, LINE_WIDTH, edge)
                val alpha = max(max(palette.fillAlpha, grid * palette.lineAlpha), glow)
                for (channel in 0 until 3) {
                    val colour = palette.fill[channel] + (YELLOW[channel] - palette.fill[channel]) * grid + GLOW[channel] * glow * 2f
                    pixels[at++] = toByte(colour).toByte()
                }
                pixels[at++] = toByte(alpha).toByte()
            }
        }
        return pixels
    }

    fun png(size: Int = 256, palette: GridPalette = GridPalette.SAMPLE, glow: Float = 0f): ByteArray {
        val pixels = rgba(size, palette, glow)
        val raw = ByteArrayOutputStream()
        for (row in 0 until size) {
            raw.write(0)
            raw.write(pixels, row * size * 4, size * 4)
        }
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            chunk("IHDR", ByteBuffer.allocate(13).putInt(size).putInt(size).put(8).put(6).put(0).put(0).put(0).array())
            chunk("IDAT", deflate(raw.toByteArray()))
            chunk("IEND", ByteArray(0))
        }.toByteArray()
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun toByte(value: Float) = (value * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION).apply {
            setInput(data)
            finish()
        }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.chunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        write(ByteBuffer.allocate(4).putInt(data.size).array())
        write(typeBytes)
        write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
}
