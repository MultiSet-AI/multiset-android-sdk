# MultiSet Android SDK

Visual Positioning System (VPS) SDK for Android applications. Achieve centimeter-level indoor localization and real-time object tracking using computer vision and AR technology.

---

## Overview

The MultiSet SDK enables visual positioning and object tracking in your Android applications. It supports both single map and map set localization, providing accurate 6DOF pose estimation with AR visualization capabilities — and a dedicated Object Tracking mode for recognizing and tracking pre-registered physical objects in AR.

### Key Features

- Visual Positioning System with centimeter-level accuracy
- Single map and map set localization support
- Real-time 6DOF pose tracking
- AR visualization with 3D mesh overlay
- Single-frame and multi-frame localization modes
- Background localization support
- GPS-assisted localization (optional)
- Customizable localization parameters
- **Object Tracking** — detect and track pre-registered physical objects in AR
- **Animated outline mesh** rendered over tracked objects with glow and spark effects
- **Background object tracking** with configurable retry intervals

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
- **Client ID** — Your unique client identifier
- **Client Secret** — Your authentication secret key
- **Map Code** or **Map Set Code** — Identifier for your mapped environment (for localization)
- **Object Code(s)** — Identifiers for pre-registered objects (for object tracking)

---

## SDK Components

The MultiSet SDK distribution includes:

| Component | Description |
|-----------|-------------|
| `multiset-sdk.aar` | Core SDK library file |
| Sample App | Reference implementation with AR activities |
| `LocalizationConfig.kt` | Customizable localization settings |
| `ObjectTrackingConfig.kt` | Customizable object tracking settings |
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
| `MULTISET_OBJECT_CODES` | Comma-separated object codes | For object tracking |

**Example `multiset.properties`:**
```
MULTISET_CLIENT_ID=your_client_id
MULTISET_CLIENT_SECRET=your_client_secret
MULTISET_MAP_CODE=your_map_code
MULTISET_MAP_SET_CODE=
MULTISET_OBJECT_CODES=object_code_1,object_code_2
```

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

## Object Tracking Configuration

The SDK provides an `ObjectTrackingConfig` object to customize tracking behavior. Configure these settings before launching `ObjectTrackingActivity`.

### Object Codes

```kotlin
ObjectTrackingConfig.objectCodes = arrayOf("object_code_1", "object_code_2")
```

Up to 10 object codes can be tracked simultaneously. Object codes are identifiers for pre-registered physical objects in the MultiSet platform.

### Tracking Behavior

| Setting | Description | Default |
|---------|-------------|---------|
| `autoTracking` | Start tracking automatically when AR session begins | true |
| `backgroundTracking` | Continue tracking in background after first success | true |
| `bgTrackingDurationSeconds` | Interval between background tracking attempts in seconds (5-30) | 15 |
| `restartTracking` | Restart tracking when AR tracking state is lost | true |
| `firstTrackingUntilSuccess` | Keep retrying silently until first track succeeds | true |
| `captureDelayMs` | Delay before capturing a frame in milliseconds | 1000 |

### Confidence Settings

| Setting | Description | Default |
|---------|-------------|---------|
| `confidenceCheck` | Reject tracking results below threshold | true |
| `confidenceThreshold` | Minimum confidence score to accept result (0.2-0.8) | 0.3 |

### UI Settings

| Setting | Description | Default |
|---------|-------------|---------|
| `showAlerts` | Display toast messages for tracking status | true |

### Image Quality

| Setting | Description | Default |
|---------|-------------|---------|
| `imageQuality` | JPEG quality for captured frames (50-100) | 80 |

### Example Setup

```kotlin
// Configure object tracking before launching the activity
ObjectTrackingConfig.objectCodes = arrayOf("chair_001", "table_002")
ObjectTrackingConfig.autoTracking = true
ObjectTrackingConfig.backgroundTracking = true
ObjectTrackingConfig.bgTrackingDurationSeconds = 15f
ObjectTrackingConfig.confidenceThreshold = 0.3f
ObjectTrackingConfig.validate()

// Launch the object tracking AR session
val intent = Intent(this, ObjectTrackingActivity::class.java)
intent.putExtra(ObjectTrackingActivity.EXTRA_OBJECT_CODES, ObjectTrackingConfig.objectCodes)
startActivity(intent)
```

---

## SDK Callbacks

Implement the `MultiSetCallback` interface to receive SDK events:

| Callback | Description |
|----------|-------------|
| `onSDKReady` | SDK initialized and ready |
| `onAuthenticationSuccess` | Authentication completed successfully |
| `onAuthenticationFailure` | Authentication failed with error |
| `onLocalizationSuccess` | Localization succeeded with result |
| `onLocalizationFailure` | Localization failed with error |
| `onTrackingStateChanged` | AR tracking state changed |
| `onObjectTrackingSuccess` | Object tracked successfully with result |
| `onObjectTrackingFailure` | Object tracking failed with error |

### Example

```kotlin
class MainActivity : AppCompatActivity(), MultiSetCallback {

    override fun onAuthenticationSuccess() {
        // SDK is ready — enable localization and tracking buttons
    }

    override fun onLocalizationSuccess(result: LocalizationResult) {
        Log.d(TAG, "Position: ${result.position.joinToString()}")
        Log.d(TAG, "Confidence: ${result.confidence}")
    }

    override fun onObjectTrackingSuccess(result: ObjectTrackingResult) {
        Log.d(TAG, "Tracked: ${result.objectCode}")
        Log.d(TAG, "Position: ${result.position.joinToString()}")
        Log.d(TAG, "Confidence: ${result.confidence}")
    }

    override fun onObjectTrackingFailure(error: String) {
        Log.e(TAG, "Tracking failed: $error")
    }
}
```

---

## Localization Result

Successful localization provides an `LocalizationResult` object via `onLocalizationSuccess`:

| Field | Description |
|-------|-------------|
| `mapCode` | Code of the localized map |
| `mapCodes` | List of all map codes returned |
| `position` | XYZ coordinates in ARCore world space |
| `rotation` | Quaternion (XYZW) orientation |
| `confidence` | Localization confidence score (if available) |
| `geoCoordinates` | Latitude, longitude, altitude (if requested) |

The result can also be retrieved at any time using `MultiSetSDK.getLastLocalizationResult()`.

---

## Object Tracking Result

Successful object tracking provides an `ObjectTrackingResult` object via `onObjectTrackingSuccess`:

| Field | Description |
|-------|-------------|
| `objectCode` | Code of the tracked object |
| `objectCodes` | List of all object codes returned |
| `position` | XYZ coordinates of the object in ARCore world space |
| `rotation` | Quaternion (XYZW) orientation of the object |
| `confidence` | Tracking confidence score |

The 3D mesh for each tracked object is automatically fetched from the MultiSet platform and rendered in AR with an animated outline effect once tracking succeeds.

---

## AR Activities

### Localization Activity (`MultiSetLocalizationActivity`)

Handles both single-frame and multi-frame localization modes. The mode is passed at launch time.

#### Single-Frame Mode

- Single-image localization
- Quick position estimation
- Lower latency

#### Multi-Frame Mode

- Multi-image localization for higher accuracy
- Captures frames at configurable intervals

**Launch:**
```kotlin
val intent = Intent(this, MultiSetLocalizationActivity::class.java)
intent.putExtra(
    MultiSetLocalizationActivity.EXTRA_LOCALIZATION_MODE,
    LocalizationMode.MULTI_FRAME.name  // or SINGLE_FRAME
)
startActivity(intent)
```

Both modes support:
- Localization animation with visual feedback
- Automatic and manual localization triggers
- Background localization
- Relocalization on tracking loss
- 3D mesh visualization
- GPS-assisted localization

---

### Object Tracking Activity (`ObjectTrackingActivity`)

Dedicated AR activity for detecting and tracking pre-registered physical objects. Once an object is tracked, its 3D mesh is fetched from the platform and placed in the AR scene with an animated glowing outline shader.

**Launch:**
```kotlin
// Option A: pass codes directly via Intent
val objectCodes = arrayOf("OBJ_001", "OBJ_002")
val intent = Intent(this, ObjectTrackingActivity::class.java)
intent.putExtra(ObjectTrackingActivity.EXTRA_OBJECT_CODES, objectCodes)
startActivity(intent)

// Option B: configure via ObjectTrackingConfig (codes are read automatically)
ObjectTrackingConfig.objectCodes = arrayOf("OBJ_001", "OBJ_002")
ObjectTrackingConfig.validate()
startActivity(Intent(this, ObjectTrackingActivity::class.java))
```

**Features:**
- Automatic tracking on session start (configurable)
- Manual tracking trigger via on-screen button
- Animated glowing outline rendered over each tracked object mesh
- Background tracking with configurable retry interval
- Auto-restart on AR tracking loss
- Close confirmation dialog

---

## Permissions

The SDK requires the following permissions (declared automatically via the AAR manifest merge):

| Permission | Purpose |
|------------|---------|
| `CAMERA` | AR camera access |
| `INTERNET` | API communication |
| `ACCESS_FINE_LOCATION` | GPS for geo hint (optional) |
| `ACCESS_COARSE_LOCATION` | GPS fallback (optional) |

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
