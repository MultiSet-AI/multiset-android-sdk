/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.localization

/**
 * Which maps' meshes are in the scene or on their way, by map code. In a map set each localized
 * map adds its own mesh and the earlier ones stay, as on iOS and in `:app`: a fix on another map
 * never cancels a download or replaces a mesh. Hidden meshes count as in the scene.
 */
class MapMeshLoads {

    private val inScene = mutableSetOf<String>()
    private val loading = mutableMapOf<String, Int>()
    private var nextTicket = 0

    /** A ticket for loading [mapCode]'s mesh, or null when it is loading or in the scene already. */
    fun start(mapCode: String): Int? {
        if (mapCode in inScene || mapCode in loading) return null
        return (++nextTicket).also { loading[mapCode] = it }
    }

    /** [inScene] is false when the mesh did not reach the scene; the next fix tries again. */
    fun finish(mapCode: String, ticket: Int, inScene: Boolean) {
        if (loading[mapCode] != ticket) return
        loading.remove(mapCode)
        if (inScene) this.inScene += mapCode
    }

    /** Forgets every mesh, so a load that ends afterwards is ignored. */
    fun clear() {
        inScene.clear()
        loading.clear()
    }
}
