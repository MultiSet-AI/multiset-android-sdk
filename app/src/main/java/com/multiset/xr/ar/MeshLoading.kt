/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import com.multiset.sdk.MultiSetSDK

/**
 * A map mesh is being fetched or built into the scene. [MultiSetSDK.isLoadingMapMesh] covers the fetch
 * alone; a fix on a map whose mesh is already in the scene turns on neither.
 */
fun MultiSetSDK.isMapMeshLoading(meshRenderer: MeshRenderer?): Boolean =
    isLoadingMapMesh || meshRenderer?.isLoading == true

/** An object mesh is being fetched or built into the scene; [MultiSetSDK.isLoadingObjectMesh] covers the fetch alone. */
fun MultiSetSDK.isObjectMeshLoading(meshRenderer: ObjectMeshRenderer?): Boolean =
    isLoadingObjectMesh || meshRenderer?.isLoading == true
