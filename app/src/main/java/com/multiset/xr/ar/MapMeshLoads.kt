/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

/**
 * Which maps' meshes are in the scene or on their way, by map code. In a map set each localized
 * map adds its own mesh and the earlier ones stay, as on iOS and in Unity: a fix on another map
 * never cancels or replaces a mesh. Main thread only.
 */
class MapMeshLoads {

    sealed interface Action {
        /** The map's mesh is in the scene: only replay its reveal. */
        data class RestartReveal(val mapId: String) : Action

        /** The map's mesh is on its way. */
        data object AlreadyLoading : Action

        /** Fetch and build the map's mesh, then report it with [ticket] to [onLoaded]. */
        data class Load(val ticket: Int) : Action
    }

    private val shown = mutableMapOf<String, String>()
    private val loading = mutableMapOf<String, Int>()
    private var nextTicket = 0

    val isLoading: Boolean get() = loading.isNotEmpty()

    fun onFix(mapCode: String): Action {
        shown[mapCode]?.let { return Action.RestartReveal(it) }
        if (mapCode in loading) return Action.AlreadyLoading
        val ticket = ++nextTicket
        loading[mapCode] = ticket
        return Action.Load(ticket)
    }

    /** [mapId] is null when the mesh could not be fetched or built; the next fix tries again. */
    fun onLoaded(mapCode: String, ticket: Int, mapId: String?) {
        if (loading[mapCode] != ticket) return
        loading.remove(mapCode)
        if (mapId != null) shown[mapCode] = mapId
    }

    /** Forgets every mesh, so a load that ends afterwards is ignored. */
    fun clear() {
        shown.clear()
        loading.clear()
    }
}
