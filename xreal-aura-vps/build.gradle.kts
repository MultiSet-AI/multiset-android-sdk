/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val multisetProperties = Properties().apply {
    val propsFile = rootProject.file("multiset.properties")
    if (propsFile.exists()) load(propsFile.inputStream())
}

fun multisetProp(key: String, default: String = "") = multisetProperties.getProperty(key, default)

android {
    namespace = "com.multiset.aura"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.multiset.aura"

        minSdk = 34
        targetSdk = 36

        versionCode = 1
        versionName = "2.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "MULTISET_CLIENT_ID",
            "\"${multisetProp("MULTISET_CLIENT_ID")}\""
        )
        buildConfigField(
            "String",
            "MULTISET_CLIENT_SECRET",
            "\"${multisetProp("MULTISET_CLIENT_SECRET")}\""
        )
        buildConfigField("String", "MULTISET_MAP_CODE", "\"${multisetProp("MULTISET_MAP_CODE")}\"")
        buildConfigField(
            "String",
            "MULTISET_MAP_SET_CODE",
            "\"${multisetProp("MULTISET_MAP_SET_CODE")}\""
        )
        buildConfigField(
            "String",
            "MULTISET_OBJECT_CODES",
            "\"${multisetProp("MULTISET_OBJECT_CODES")}\""
        )
        // Optional API host override; empty means the SDK uses its production default.
        buildConfigField("String", "MULTISET_BASE_URL", "\"${multisetProp("MULTISET_BASE_URL")}\"")
        // Optional token endpoint answering {"token": "<JWT>"}; set, the app never uses the client secret.
        buildConfigField("String", "MULTISET_TOKEN_SERVER_URL", "\"${multisetProp("MULTISET_TOKEN_SERVER_URL")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // MultiSet SDK AAR: the one copy in :app's libs, so both samples always ship the same SDK
    implementation(files("../app/libs/multiset-sdk.aar"))

    // A file dependency carries no transitive metadata, so everything the SDK exposes
    // through api(...) has to be declared here or it goes missing at runtime.
    implementation(libs.okhttp3.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom.xr))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Jetpack XR, pinned: anchor handling and node-rotation detection are tied to these versions
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.arcore)
    implementation(libs.extensions1.xr)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
