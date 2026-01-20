import java.util.Properties
import kotlin.apply

/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can’t re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ============================================================
// MULTISET SDK CONFIGURATION
// Configure your credentials in: multiset.properties
// Get credentials at: https://developer.multiset.ai/credentials
// ============================================================
val multisetProperties = Properties().apply {
    val propsFile = rootProject.file("multiset.properties")
    if (propsFile.exists()) {
        load(propsFile.inputStream())
    }
}

fun getMultisetProperty(key: String, default: String = ""): String {
    return multisetProperties.getProperty(key, default)
}

android {
    namespace = "com.multiset.sdk.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.multiset.sdk.android"

        minSdk = 28
        targetSdk = 36

        versionCode = 11
        versionName = "1.10.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MultiSet SDK Configuration (loaded from multiset.properties)
        buildConfigField(
            "String",
            "MULTISET_CLIENT_ID",
            "\"${getMultisetProperty("MULTISET_CLIENT_ID")}\""
        )
        buildConfigField(
            "String",
            "MULTISET_CLIENT_SECRET",
            "\"${getMultisetProperty("MULTISET_CLIENT_SECRET")}\""
        )

        buildConfigField(
            "String",
            "MULTISET_MAP_CODE",
            "\"${getMultisetProperty("MULTISET_MAP_CODE")}\""
        )
        buildConfigField(
            "String",
            "MULTISET_MAP_SET_CODE",
            "\"${getMultisetProperty("MULTISET_MAP_SET_CODE")}\""
        )

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // MultiSet SDK AAR
    implementation(files("libs/multiset-sdk.aar"))


    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // ARCore
    implementation(libs.core)
    implementation(libs.sceneform)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.logging.interceptor)
    implementation(libs.okhttp3.okhttp)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Image Processing
    implementation(libs.vision.common)
}