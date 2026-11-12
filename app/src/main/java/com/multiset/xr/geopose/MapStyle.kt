/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.geopose

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

/** Base maps that need no API key, only their attribution: OSM, Esri World Imagery, CARTO. */
enum class MapStyle(val attribution: String) {
    STREET("© OpenStreetMap contributors"),
    SATELLITE("© Esri, Maxar, Earthstar Geographics"),
    LIGHT("© OpenStreetMap contributors © CARTO"),
    DARK("© OpenStreetMap contributors © CARTO");

    fun tileSource(): ITileSource = when (this) {
        STREET -> TileSourceFactory.MAPNIK
        SATELLITE -> ESRI_WORLD_IMAGERY
        LIGHT -> CARTO_LIGHT
        DARK -> CARTO_DARK
    }

    private companion object {
        val ESRI_WORLD_IMAGERY = online(
            name = "EsriWorldImagery",
            urls = arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
            attribution = SATELLITE.attribution,
            suffix = "",
            columnFirst = false,
            maxZoom = 19,
        )

        val CARTO_LIGHT = carto("CartoLight", "light_all", LIGHT.attribution)

        val CARTO_DARK = carto("CartoDark", "dark_all", DARK.attribution)

        fun carto(name: String, layer: String, attribution: String) = online(
            name = name,
            urls = arrayOf("a", "b", "c").map { "https://$it.basemaps.cartocdn.com/$layer/" }.toTypedArray(),
            attribution = attribution,
            suffix = ".png",
            columnFirst = true,
        )

        // Tile paths are z/x/y for OSM and CARTO ([columnFirst]) and z/y/x for Esri.
        fun online(
            name: String,
            urls: Array<String>,
            attribution: String,
            suffix: String,
            columnFirst: Boolean,
            maxZoom: Int = 20,
        ): OnlineTileSourceBase = object : OnlineTileSourceBase(name, 0, maxZoom, 256, suffix, urls, attribution) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return if (columnFirst) "$baseUrl$z/$x/$y$suffix" else "$baseUrl$z/$y/$x$suffix"
            }
        }
    }
}
