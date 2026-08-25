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

// ============================================================
// MULTISET SDK CONFIGURATION
// Copy multiset.properties.template -> multiset.properties and fill it in.
// Get credentials at: https://developer.multiset.ai/credentials
// ============================================================
val multisetProperties = Properties().apply {
    val propsFile = rootProject.file("multiset.properties")
    if (propsFile.exists()) load(propsFile.inputStream())
}
fun multisetProp(key: String, default: String = "") = multisetProperties.getProperty(key, default)

android {
    namespace = "com.multiset.xr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.multiset.xr"

        minSdk = 28
        targetSdk = 36

        versionCode = 16
        versionName = "1.16.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MULTISET_CLIENT_ID", "\"${multisetProp("MULTISET_CLIENT_ID")}\"")
        buildConfigField("String", "MULTISET_CLIENT_SECRET", "\"${multisetProp("MULTISET_CLIENT_SECRET")}\"")
        buildConfigField("String", "MULTISET_MAP_CODE", "\"${multisetProp("MULTISET_MAP_CODE")}\"")
        buildConfigField("String", "MULTISET_MAP_SET_CODE", "\"${multisetProp("MULTISET_MAP_SET_CODE")}\"")
        buildConfigField("String", "MULTISET_OBJECT_CODES", "\"${multisetProp("MULTISET_OBJECT_CODES")}\"")
        // Optional API host override; empty means the SDK uses its production default.
        buildConfigField("String", "MULTISET_BASE_URL", "\"${multisetProp("MULTISET_BASE_URL")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Required for 16KB page size compatibility
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // MultiSet SDK AAR
    implementation(files("libs/multiset-sdk.aar"))

    // A file dependency carries no transitive metadata, so everything the SDK exposes
    // through api(...) has to be declared here or it goes missing at runtime.
    implementation(libs.okhttp3.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.vision.common)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Compose launcher
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ARCore + Sceneform (AR sample only)
    implementation(libs.core)
    implementation(libs.sceneform)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
