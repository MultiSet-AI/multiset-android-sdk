# MultiSet Android SDK

Visual Positioning System (VPS) SDK for Android applications. Achieve centimeter-level indoor localization using computer vision and AR technology.

---

## Overview

The MultiSet SDK enables visual positioning in your Android applications. It supports both single map and map set localization, providing accurate 6DOF pose estimation with AR visualization capabilities.

### Key Features

- Visual Positioning System with centimeter-level accuracy
- Single map and map set localization support
- Real-time 6DOF pose tracking
- AR visualization with 3D mesh overlay
- Single-frame and multi-frame localization modes
- Background localization support
- GPS-assisted localization (optional)
- Customizable localization parameters

---

## Requirements

| Requirement | Minimum |
|-------------|---------|
| Android API Level | 28 (Android 9.0) |
| Target SDK | 36 |
| Java Version | 17 |
| Kotlin Version | 2.2.0+ |
| Device | ARCore-supported with camera |

### Credentials

Before integrating, obtain your credentials from the MultiSet Developer Portal:

**https://developer.multiset.ai/credentials**

You will need:
- **Client ID** - Your unique client identifier
- **Client Secret** - Your authentication secret key
- **Map Code** or **Map Set Code** - Identifier for your mapped environment

---

## SDK Components

The MultiSet SDK distribution includes:

| Component | Description |
|-----------|-------------|
| `multiset-sdk.aar` | Core SDK library file |
| Sample App | Reference implementation with AR activities |
| `LocalizationConfig.kt` | Customizable localization settings |
| Layout Resources | AR activity layouts and drawables |

---

## Integration Steps

### Step 1: Add the AAR File

Place the `multiset-sdk.aar` file in your project's `app/libs/` directory.

### Step 2: Configure Dependencies

Add the SDK and required dependencies to your app module's build configuration. The SDK requires:

- ARCore 1.46.0
- Sceneform 1.24.6 (RGregat fork with 16KB page size support)
- OkHttp & Retrofit for networking
- Kotlin Coroutines
- AndroidX libraries

### Step 3: Add JitPack Repository

The Sceneform library requires the JitPack repository in your project settings.

### Step 4: Configure Packaging Options

Enable legacy JNI packaging for 16KB page size compatibility with Sceneform's native libraries.

---

## SDK Configuration

### Credentials Setup

Create a `multiset.properties` file in your project root with your credentials:

| Property | Description | Required |
|----------|-------------|----------|
| `MULTISET_CLIENT_ID` | Your client identifier | Yes |
| `MULTISET_CLIENT_SECRET` | Your secret key | Yes |
| `MULTISET_MAP_CODE` | Single map identifier | One of these |
| `MULTISET_MAP_SET_CODE` | Map set identifier | is required |

A template file `multiset.properties.template` is provided for reference.

**Important:** Add `multiset.properties` to your `.gitignore` to protect credentials.

---

## Localization Configuration

The SDK provides extensive customization through the `LocalizationConfig` object. Configure these settings before launching AR activities.

### Localization Behavior

| Setting | Description | Default |
|---------|-------------|---------|
| Auto Localize | Start localization automatically when AR session begins | true |
| Background Localization | Continue localizing after first success | true |
| Background Interval | Seconds between background localizations (15-180) | 30 |
| Relocalization | Re-localize when tracking is lost | true |
| First Until Success | Keep retrying until first localization succeeds | true |

### Multi-Frame Capture

| Setting | Description | Default |
|---------|-------------|---------|
| Number of Frames | Frames to capture for multi-frame mode (4-6) | 4 |
| Capture Interval | Milliseconds between frame captures (100-1000) | 500 |

### Confidence Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Confidence Check | Reject localizations below threshold | false |
| Confidence Threshold | Minimum confidence score (0.0-1.0) | 0.3 |

### GPS Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Enable Geo Hint | Send GPS coordinates to improve localization | false |
| Include Geo Response | Include geo coordinates in result | false |

**Note:** Geo hint requires location permissions and a geo-referenced map.

### UI Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Show Alerts | Display toast messages for status | true |
| Mesh Visualization | Show 3D mesh overlay after localization | true |

### Image Quality

| Setting | Description | Default |
|---------|-------------|---------|
| Image Quality | JPEG quality for captured images (50-100) | 90 |

---

## SDK Callbacks

Implement the callback interface to receive SDK events:

| Callback | Description |
|----------|-------------|
| `onSDKReady` | SDK initialized and ready |
| `onAuthenticationSuccess` | Authentication completed successfully |
| `onAuthenticationFailure` | Authentication failed with error |
| `onLocalizationSuccess` | Localization succeeded with result |
| `onLocalizationFailure` | Localization failed with error |
| `onTrackingStateChanged` | AR tracking state changed |
| `onMeshLoaded` | 3D mesh loaded successfully |
| `onMeshLoadError` | Mesh loading failed |

---

## Localization Result

Successful localization provides:

| Field | Description |
|-------|-------------|
| Map Code | Code of the localized map |
| Map Codes | List of all map codes returned by localization |
| Position | XYZ coordinates in ARCore world space |
| Rotation | Quaternion (XYZW) orientation |
| Confidence | Localization confidence score (if available) |
| Geo Coordinates | Latitude, longitude, altitude (if requested) |

The localization result is delivered via `onLocalizationSuccess` callback and can also be retrieved at any time using `MultiSetSDK.getLastLocalizationResult()`.

---

## AR Activity

The SDK uses a unified `MultiSetLocalizationActivity` that handles both single-frame and multi-frame localization modes within a single activity. The mode is selected at launch time based on your SDK configuration.

### Single-Frame Mode

- Single-image localization
- Quick position estimation
- Lower latency

### Multi-Frame Mode

- Multi-image localization
- Higher accuracy
- Captures frames at configurable intervals

Both modes share the same activity and support:
- Localization animation with visual feedback
- Automatic and manual localization triggers
- Background localization
- Relocalization on tracking loss
- 3D mesh visualization
- GPS-assisted localization

---

## Permissions

The SDK requires the following permissions:

| Permission | Purpose |
|------------|---------|
| CAMERA | AR camera access |
| INTERNET | API communication |
| ACCESS_FINE_LOCATION | GPS for geo hint (optional) |
| ACCESS_COARSE_LOCATION | GPS fallback (optional) |

Location permissions are only required if using GPS-assisted localization.

---
## Support

- **Documentation:** https://developer.multiset.ai/docs
- **Developer Portal:** https://developer.multiset.ai
- **Email:** support@multiset.ai

---

## License

Copyright (c) 2026 MultiSet AI. All rights reserved.

Licensed under the MultiSet License. You may not use this file except in compliance with the License. Redistribution in source or binary forms must retain this notice.

For license details, visit www.multiset.ai.
