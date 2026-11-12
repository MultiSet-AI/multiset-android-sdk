# MultiSet Android SDK

Visual Positioning System (VPS) SDK for Android: centimetre-level localization against your MultiSet maps, real-time object tracking, and map scanning on the device.

---

## Overview

| Item | Contents |
|------|----------|
| `app/libs/multiset-sdk.aar` | The SDK: authentication, networking, capture scheduling, pose math, mesh loading, map scanning and upload. **No ARCore, Sceneform or Jetpack XR dependency.** |
| `:app` | ARCore + Sceneform sample: localization, object tracking and map-scanning screens. |
| `:xreal-aura-vps` | Android XR sample for XREAL Aura glasses: Camera2 and the Jetpack XR head pose instead of ARCore, SceneCore instead of Sceneform. |

The SDK never touches an AR runtime. Your app turns each camera frame into a plain `CameraFrame` and hands it over through the `FrameSource` interface, so the same library runs on ARCore or any other Android XR runtime. Map scanning works the same way, through `MultiSetMappingSource`.

### Key Features

- Map and MapSet localization, single-frame or multi-frame, with VPS-1 / VPS-2 query modes
- Background localization, and relocalization after a tracking break
- Object tracking for up to 10 pre-registered objects
- Optional false-positive rejection against the device's own AR trajectory
- Optional GPS hint, and the fix in geographic terms
- 3D map and object mesh loading
- Non-LiDAR map scanning, with drafts and upload to your account
- Automatic token refresh and typed failures (`MultiSetError`)
- A token provider, so the client secret can stay on your own server
- Configurable API host for staging and on-premise deployments

---

## What's New in 2.2.0

| Change | Notes |
|--------|-------|
| **Token provider** | Keep the client secret on your own server. Pass a `MultiSetTokenProvider` as `tokenProvider`, or build with `MultiSetSDKConfig.Builder(provider)`, and the SDK asks your code for each token with a reason: `INITIAL`, `EXPIRING` or `REJECTED`. See [Keeping the Client Secret on Your Server](#keeping-the-client-secret-on-your-server) and [SERVER_AUTH_GUIDE.md](SERVER_AUTH_GUIDE.md). |
| **Samples: token server** | Set `MULTISET_TOKEN_SERVER_URL` in `multiset.properties` and both samples get their tokens from your endpoint through `TokenServerProvider`, never using the client ID and secret. See [Credentials](#2-credentials). |
| **Sample: Geo Pose Map View** | A new card on the sample's home screen opens a map that plots each fix's GeoPose, its position and compass heading, beside the device GPS track. It needs a geo-referenced map or MapSet. See [Sample App](#sample-app-app) and [Reading the GeoPose heading](#reading-the-geopose-heading). |
| **A refused token is not resent** | A request refused with HTTP 401 is no longer sent again when the renewed token is the one that was refused. |

**Upgrading from 2.1.0** needs no source changes: `clientId` and `clientSecret` keep working as before. Recompile against the new AAR, since `MultiSetSDKConfig` gained the `tokenProvider` parameter. The dependency list is unchanged.

### Earlier releases

- **2.1.0:** Non-LiDAR map scanning with drafts and upload, the XREAL Aura sample, relocalization after a tracking break, object tracking across breaks, a silent first-fix retry, run stages, VPS-2 for multi-frame, hints and the GPS hint sent with every query, `imageLongSide`, new defaults (single-frame VPS-1, `poseConsistencyCheck = false`), correct MapSet mesh placement, and fewer dependencies (no `logging-interceptor` or `vision-common`). Upgrading from 2.0.0 needs no source changes; check the [behaviour changes](#upgrading-from-200).
- **2.0.0:** an anchor-based pose consistency gate with `onLocalizationCorrected` and `onAnchorTrustChanged`, flat distance and heading limits, `notifyWorldOriginReset()`, `CameraFrame.timestampSeconds` and `resetLocalizationSession()`. Upgrading from 1.16.0 is source-breaking: see [Upgrading to 2.0.0](#upgrading-to-200).
- **1.16.0:** the AR-free SDK (`FrameSource` and sessions), false-positive rejection, a configurable API host, query mode, typed failures and automatic token refresh.

### Upgrading to 2.0.0

- **`poseConsistencyThreshold` narrowed from 3–30 m to 1.5–15 m.** A value outside the range now throws `IllegalArgumentException` when the config is built.
- **`GateTuning` dropped `rejectionBaseM`, `driftRateMPerSec` and `rejectionMaxM`.** Move to the single flat `rejectionDistanceM`; the drift rate has no replacement.
- **`FalsePositiveInfo` and `GateDiagnostics` gained constructor parameters mid-list**, so positional `componentN` access and `copy()` calls in your own code shift.

---

## Requirements

| Requirement | Minimum |
|-------------|---------|
| Android API level | 28 (Android 9.0); 34 for `:xreal-aura-vps` |
| Target SDK | 36 |
| Java / Kotlin | 17 / 2.2.0+ |
| Gradle / AGP | 8.11.1 / 8.10.0 |
| Device | ARCore-supported phone (`:app`), or Android XR glasses such as XREAL Aura (`:xreal-aura-vps`) |

The SDK needs only a camera image, a pose and camera intrinsics; ARCore is a requirement of the sample app, not of the SDK.

Get your client ID, client secret and map, MapSet or object codes from the [MultiSet Developer Portal](https://developer.multiset.ai/credentials).

---

## Integration

### 1. Add the AAR and its dependencies

Copy `multiset-sdk.aar` into your module's `libs/` folder. A file dependency carries no dependency metadata, so declare what the SDK uses yourself, or the app compiles and then fails at runtime with `NoClassDefFoundError`:

```kotlin
dependencies {
    implementation(files("libs/multiset-sdk.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.18.0")
}
```

Rendering with Sceneform, as `:app` does, also needs ARCore 1.57.0, Sceneform 1.24.6 (the RGregat fork with 16 KB page-size support, from JitPack) and `packaging.jniLibs.useLegacyPackaging = true`. None of these is needed to drive the SDK from another runtime.

### 2. Credentials

The samples read credentials at build time from `multiset.properties` at the repository root. Keep it out of version control:

```bash
cp multiset.properties.template multiset.properties
echo "multiset.properties" >> .gitignore
```

```properties
MULTISET_BASE_URL=
MULTISET_CLIENT_ID=your_client_id
MULTISET_CLIENT_SECRET=your_client_secret
MULTISET_TOKEN_SERVER_URL=
MULTISET_MAP_CODE=your_map_code
MULTISET_MAP_SET_CODE=
MULTISET_OBJECT_CODES=OBJ_001, OBJ_002
```

| Property | Notes |
|----------|-------|
| `MULTISET_CLIENT_ID`, `MULTISET_CLIENT_SECRET` | Required unless `MULTISET_TOKEN_SERVER_URL` is set. With either one empty, the app reports the SDK as not configured. |
| `MULTISET_TOKEN_SERVER_URL` | Optional. Your endpoint answering a GET with `{"token": "<JWT>"}`. When set, both samples get their tokens there, adding `reason=initial`, `expiring` or `rejected`, and never use the client ID and secret, which may stay empty. `http://` endpoints work in debug builds only. |
| `MULTISET_MAP_CODE` or `MULTISET_MAP_SET_CODE` | Fill one. If both are set, `:app` uses the map code; the Aura sample offers a choice in Settings. |
| `MULTISET_OBJECT_CODES` | Comma-separated, up to 10, for object tracking. |
| `MULTISET_BASE_URL` | Optional API host for staging or on-premise; empty means `https://api.multiset.ai`. `http://` hosts work in debug builds only. |

At least one map, MapSet or object code is required. Write each value as `KEY=value`, with no quotes or trailing comment, and **rebuild after every edit**: the values are compiled into `BuildConfig`.

### 3. Initialize

```kotlin
val config = MultiSetSDKConfig.Builder(clientId, clientSecret)
    .mapCode("your_map_code")                          // or .mapSetCode(...) / .objectCodes(...)
    .localizationMode(LocalizationMode.SINGLE_FRAME)   // the default, with QueryMode.VPS1
    .build()

MultiSetSDK.initialize(applicationContext, config, callback)
```

A secret in your app can be read by anyone who unpacks it. To keep it on your own server, pass a `tokenProvider` instead; see [Keeping the Client Secret on Your Server](#keeping-the-client-secret-on-your-server).

`MultiSetSDKConfig` validates on construction. It throws on blank credentials without a `tokenProvider`, credentials alongside one, a missing map or object code, more than 10 object codes, a malformed `baseUrl`, or a value out of range: `numberOfFrames` 4–6, `imageQuality` 1–100, `imageLongSide` 1–1280, `confidenceThreshold` 0–1, `poseConsistencyThreshold` 1.5–15 m, `poseConsistencyYawThreshold` 10–60°, `hintRadius` 1–100.

**Query image size.** Each frame is scaled uniformly, up or down, so its longer side is `imageLongSide` pixels (default 960, at most 1280). Nothing is cropped or stretched, and the intrinsics are scaled with it: ARCore's 640 × 480 image goes out at 960 × 720.

---

## The Platform-Agnostic Boundary

To run the SDK on any XR runtime, implement one interface:

```kotlin
interface FrameSource {
    suspend fun acquire(): CameraFrame?   // null when no frame is ready
}

data class CameraFrame(
    val bitmap: Bitmap?,                // RGB camera image (null skips the frame)
    val cameraPosition: Vec3,           // world position (x, y, z)
    val cameraRotation: Quat,           // world rotation (x, y, z, w)
    val intrinsics: CameraIntrinsics,   // fx, fy, cx, cy, imageWidth, imageHeight
    val orientation: DeviceOrientation  // PORTRAIT or LANDSCAPE
)
```

- Pass the **raw, uncorrected** camera pose. The SDK applies the portrait correction (bitmap rotation, quaternion and intrinsics) itself; correcting it beforehand rotates twice.
- Set `timestampSeconds` to the runtime's monotonic capture time (ARCore `frame.timestamp / 1e9`); the pose consistency check uses it as its clock.
- Start a session only once the runtime is tracking, or it fails with "Failed to capture frame".

The SDK owns everything else: capture scheduling, retry, background localization, confidence gating, JPEG encoding, networking and pose math. The sample's `ar/ArFrameSource.kt` shows the ARCore glue.

---

## SDK Callbacks

Implement `MultiSetSDKCallback`. Authentication events come from `initialize`; the rest come from the sessions `MultiSetSDK` builds, which report here as well as through their own lambdas.

| Callback | Description |
|----------|-------------|
| `onSDKReady()` | SDK initialized |
| `onAuthenticationSuccess()` / `onAuthenticationFailure(error)` | Sign-in result |
| `onLocalizationSuccess(result)` / `onLocalizationFailure(error)` | Localization result |
| `onLocalizationFalsePositive(info)` | A fix was rejected as inconsistent with the AR trajectory *(optional)* |
| `onLocalizationCorrected(from, to, trust)` | Agreeing fixes moved the anchor; move your own world-space content *(optional)* |
| `onAnchorTrustChanged(trust)` | The anchor's standing changed *(optional)* |
| `onRecoveryPhaseChanged(phase, needsRealignment)` | Recovery after a tracking break moved on; hide map content while `needsRealignment` *(optional)* |
| `onLocalizationStageChanged(stage, isBackground)` | A localization run moved to its next stage *(optional)* |
| `onTrackingStateChanged(state)` | A session was told the AR tracking state |
| `onObjectTrackingSuccess(result)` / `onObjectTrackingFailure(error)` | Object tracking result *(optional)* |
| `onObjectTrackingStageChanged(stage, isBackground)` | An object tracking run moved to its next stage *(optional)* |

Optional callbacks have no-op defaults.

```kotlin
class MainActivity : AppCompatActivity(), MultiSetSDKCallback {
    override fun onSDKReady() {}
    override fun onAuthenticationSuccess() { /* enable localization and tracking */ }
    override fun onAuthenticationFailure(error: String) { Log.e(TAG, "Auth failed: $error") }
    override fun onLocalizationSuccess(result: LocalizationResult) { Log.d(TAG, "Pose: ${result.position.joinToString()}") }
    override fun onLocalizationFailure(error: String) {}
    override fun onTrackingStateChanged(state: TrackingState) {}
}
```

---

## Sessions

Sessions run the capture loop for you, pulling frames from your `FrameSource`.

**Localization:**

```kotlin
val session = MultiSetSDK.localizationSession(frameSource)   // settings default to the config
session.onLocalizationSuccess = { result -> /* result.position, result.rotation */ }
session.onLocalizationFailure = { error -> /* MultiSetError */ }
session.onLocalizationFalsePositive = { info -> /* rejected fix */ }
session.onStageChanged = { stage, isBackground -> /* Capturing / Uploading / AwaitingResponse / Idle */ }
session.onRecoveryPhaseChanged = { phase, needsRealignment -> /* hide the map while needsRealignment */ }
session.start()                                              // refused while session.isBusy
session.stop()
```

**Object tracking:**

```kotlin
val session = MultiSetSDK.objectTrackingSession(frameSource) // codes default to the config
session.onTrackingSuccess = { result -> /* place result.objectCode's own anchor */ }
session.onTrackingFailure = { error -> /* MultiSetError */ }
session.onPlacementVisibilityChanged = { objectCode, visible -> /* hide a stale outline */ }
session.start()
session.resetSession()                                       // forget placements, re-arm the first track
```

Session failure lambdas receive a typed `MultiSetError`; the SDK-wide callback receives a `String`.

### Run stages

Each run reports `Capturing(captured, required)` while frames are taken, `Uploading` (for 2 s or until the answer), `AwaitingResponse`, then `Idle`, before its result callback. `isBackground` is true for background refreshes, recoveries and automatic re-tracks: show those without covering the scene. While a silent first-fix retry is due the run is `Idle` but `isAwaitingRetry` is true, so keep the user's overlay up; `isBusy` covers both.

### Tracking breaks

Report every AR tracking change to the session, including the first state and the return to tracking:

```kotlin
session.reportTrackingState(TrackingState.TRACKING)  // also fires a pending recovery after 1 s
session.reportTrackingState(TrackingState.PAUSED)    // limited tracking: 2 s grace before it counts
session.notifyTrackingInterrupted()                  // camera lost (ARCore CAMERA_UNAVAILABLE)
session.notifyWorldOriginReset()                     // BAD_STATE, STOPPED, or back from the background
```

With `relocalization` on (the default), a break after a fix arms a **recovery**: once tracking has held for 1 s, the session re-localizes, retrying every second without reporting misses while the scene is out of place. `recoveryPhase` (`NONE`, `WAITING_FOR_TRACKING`, `REALIGNING`) and `needsRealignment` say where it stands: hide the map while `needsRealignment` is true. Nothing is recovered until you have called `reportTrackingState` at least once.

ARCore cannot reset its world frame, and a session resumed from the background may no longer match the room. The sample therefore treats the return as a world-origin reset: in `onPause`, before `super.onPause()`, it calls `reportTrackingState(PAUSED)` and then `notifyWorldOriginReset()`. A `FrameSource` should report `isHostActive = false` while the app is in the background.

The object tracking session takes the same calls. With `restartTracking` on, it re-tracks once tracking has held for 1 s. An interruption hides every placed object through `onPlacementVisibilityChanged(code, false)` until tracking returns; after a world-origin reset each object stays hidden until it is tracked again.

### Upgrading from 2.0.0

- **Single frame is the default localization mode**, with VPS-1. Pass `.localizationMode(LocalizationMode.MULTI_FRAME)` to keep the old default.
- **`poseConsistencyCheck` defaults to `false`.** Pass `true` to keep the check.
- **`queryMode` and every hint and GPS setting are now sent**, multi-frame included. Before, only `hintMapCodes` was.
- **First-until-success is capped at 5 silent retries** and no longer needs background localization.
- **`start()` is refused while the session is busy**, including a silent retry's wait.
- **Object tracking has its own settings:** `backgroundObjectTracking` and `firstObjectTrackingUntilSuccess` (both on by default), plus the interval, capture delay and restart. It no longer reads the localization ones.
- **`MultiSetSDKCallback` also hears the sessions `MultiSetSDK` builds.** If you handle both the callback and the session lambdas, handle each event in one place.
- **Recompile against the new AAR.** `localizeSingleFrame`'s `queryMode` is now nullable (null uses the config's), both localization raw APIs gained a `geoHint` parameter, and `MultiSetSDKConfig` and `LocalizationResult` gained constructor parameters.

---

## Localization Configuration

The sample exposes these through `LocalizationConfig` and its Settings dialog. The same settings exist on `MultiSetSDKConfig` and `LocalizationSession`.

| Setting | Default | Notes |
|---------|---------|-------|
| Auto Localize | on | Start once the AR session tracks |
| Background Localization | on | Keep localizing after a fix, every Background Interval (10–180 s, default 30) |
| Relocalization | on | Re-localize after a tracking break ([details](#tracking-breaks)) |
| First Until Success | on | Retry a failed first fix silently, up to 5 times, before reporting it |
| Number of Frames / Capture Interval | 4 / 500 ms | Multi-frame capture (4–6 frames, 100–1000 ms) |
| `queryMode` | `VPS1` | `VPS1` (standard) or `VPS2` (deep search), single- and multi-frame |
| Confidence Check / Threshold | off / 0.3 | Reject fixes below the threshold (0–1) |
| Image Quality | 90 | JPEG quality (50–100) |
| Show Alerts / Mesh Visualization | on / on | Sample UI |

First-until-success retries 0.5 s apart, backing off to 4, 8 and 12 s after a timeout, an HTTP 5xx or a 429. Any other HTTP 4xx is reported at once. It applies only to the first fix of a session, and `resetSession()` re-arms it.

### Pose Consistency (False-Positive Check)

| Setting | Default | Notes |
|---------|---------|-------|
| `poseConsistencyCheck` | **off** | Reject fixes that contradict the device's AR trajectory |
| `poseConsistencyThreshold` | 4 m | Distance limit from the anchor (1.5–15) |
| `poseConsistencyYawThreshold` | 25° | Heading limit from the anchor (10–60) |
| `poseConsistencyTuning` | `null` | `GateTuning` overrides |
| `mapAliasingRisk` | empty | Per-map multiplier on the viewpoints needed to move the anchor, for repetitive spaces, e.g. `mapOf("LOBBY_L2" to 2f)` |

The first fix sets the **anchor**: `TRUSTED` from a multi-frame query, `PROVISIONAL` from a single frame. A later fix within both limits refines it. A fix beyond either limit is contested: it moves the anchor only when enough fixes from distinct viewpoints agree on it (3 for a trusted anchor, 2 for a corroborated one, 1 for a provisional one), and `onLocalizationCorrected(from, to, trust)` then fires before the success. Otherwise `onLocalizationFalsePositive(info)` fires and the scene is untouched. After two consecutive rejections the session sends one multi-frame query (5 s cooldown).

| `FalsePositiveInfo` | Description |
|---------------------|-------------|
| `jumpMeters`, `yawDeltaDeg` | How far the rejected fix was from the anchor |
| `thresholdMeters`, `thresholdDegrees` | The limits in force |
| `disagreement` | `DISTANCE`, `HEADING` or `BOTH` |
| `cause` | `INSUFFICIENT_VIEWPOINTS` (move and ask again), `ANCHOR_HELD_VOTE` or `INLIER_MARGIN` |
| `rejection` | `FALSE_POSITIVE`, `REESTABLISHING` (retried quietly) or `SUSPECTED_ALIASING` (re-localizing will not help) |
| `challengerSupport`, `requiredSupport`, `consecutiveCount`, `trust` | Viewpoints behind the fix and needed to move the anchor, the rejection streak, the anchor's standing |
| `mapCodes`, `confidence`, `summary` | The rejected fix and a log-ready line |

```kotlin
MultiSetSDK.resetPoseConsistencyReference()   // the anchor is fine; re-bootstrap the reference
MultiSetSDK.resetLocalizationSession()        // the map itself is misplaced; start over
```

`resetLocalizationSession()` is the only way out of a bad **first** fix, which sets the anchor unchecked. Offer it from your false-positive UI, and remove your map mesh with it. To rebuild the runtime's world origin on a reset, set `session.worldOriginResetHandler`; leaving it unset is supported.

### GPS and Localization Hints

| Setting | Default | Notes |
|---------|---------|-------|
| `enableGeoHint` | off | Send the device's GPS fix as a hint (needs a geo-referenced map) |
| `includeGeoCoordinatesInResponse` | off | Return the fix in geographic terms: `geoCoordinates` and `geoPose` |
| `hintMapCodes` | empty | Restrict a MapSet search to these maps (MapSet only) |
| `hintPosition` | empty | Approximate position, `"x,y,z"` |
| `hintFloorHeight` | empty | Floor and ceiling, `"floor,ceiling"`, e.g. `"0,5"` |
| `hintRadius` | 25 | Search radius in metres around a geo or position hint (1–100) |
| `use2DFiltering` | off | Ignore altitude when filtering by the geo hint |

The SDK has no location stack: you supply the position and own the permission.

```kotlin
MultiSetSDK.geoHintProvider = GeoHintProvider { latestFix }   // GeoCoordinates?; null sends nothing
```

`hintRadius` and `use2DFiltering` are sent only with a geo or position hint, and `use2DFiltering` is forced for a hint without altitude.

---

## Object Tracking Configuration

The sample exposes these through `ObjectTrackingConfig`. On `MultiSetSDKConfig` they are `autoObjectTracking`, `backgroundObjectTracking`, `backgroundObjectTrackingIntervalSeconds`, `restartObjectTracking`, `firstObjectTrackingUntilSuccess` and `objectTrackingCaptureDelayMs`.

| Setting | Default | Notes |
|---------|---------|-------|
| `objectCodes` | | Up to 10 pre-registered objects |
| `autoTracking` | on | Start once the AR session tracks |
| `backgroundTracking`, `bgTrackingDurationSeconds` | on, 15 s | Re-track at this interval (5–30 s) |
| `restartTracking` | on | Re-track once AR tracking holds again after a loss |
| `firstTrackingUntilSuccess` | on | Retry a failed first track silently, up to 5 times |
| `captureDelayMs` | 1000 | Hold before each capture (500–3000 ms) |
| `confidenceCheck`, `confidenceThreshold` | on, 0.3 | Reject results below the threshold (0.2–0.8) |
| `imageQuality` | 80 | JPEG quality (50–100) |
| `showAlerts` | on | Sample UI |

Give each tracked object its **own** anchor: one result moves one object.

```kotlin
ObjectTrackingConfig.objectCodes = arrayOf("OBJ_001", "OBJ_002")
ObjectTrackingConfig.validate()
startActivity(Intent(this, ObjectTrackingActivity::class.java))
```

---

## Authentication and Token Lifetime

Authentication is automatic. The M2M token lives 5 minutes and the SDK renews it ahead of expiry; concurrent requests share one renewal, and a request rejected with HTTP 401 is retried once with a fresh token, reporting no failure if that succeeds. Calling `initialize` again with the same account (the same client ID and secret, or an equal `tokenProvider`), for example after a rotation, sends no request while the token is valid. Hosts that call the MultiSet API themselves can use `MultiSetSDK.currentToken()`.

### Keeping the Client Secret on Your Server

Instead of `clientId` and `clientSecret`, give the SDK a token provider. Your server keeps the secret, gets MultiSet access tokens, and hands them to the app; the SDK sends that token on every API call and asks for a new one when it needs to. [SERVER_AUTH_GUIDE.md](SERVER_AUTH_GUIDE.md) walks through both sides, with a server example.

```kotlin
val provider = MultiSetTokenProvider { reason ->
    myBackend.multiSetToken(skipCache = reason == MultiSetTokenRequestReason.REJECTED)
}

val config = MultiSetSDKConfig.Builder(provider)
    .mapCode("your_map_code")
    .build()

MultiSetSDK.initialize(applicationContext, config, callback)
```

Your server gets each token the way the SDK otherwise would, from the environment `baseUrl` points at:

```bash
curl -X POST https://api.multiset.ai/v1/m2m/token -u "$CLIENT_ID:$CLIENT_SECRET"
# {"token": "<JWT>", "expiresOn": "..."}
```

- The provider is asked with a reason: `INITIAL` from `initialize`, `EXPIRING` before the current token runs out, and `REJECTED` after the API refused it with a 401. On `REJECTED`, return a newly issued token, not a cached copy.
- Return only the token string: the SDK reads the expiry from the token's own `exp`, so your server's `expiresOn` is not needed. A token your server hands out part used, from its cache, is renewed before its `exp`.
- Your server may cache a token across devices, but should hand out a new one once the cached copy has under 5 minutes left.
- A request refused with a 401 is retried once with the replacement. If that is refused too, or the provider returns the refused token again, the request fails with the 401.
- A provider that throws or returns an empty string fails `initialize` through `onAuthenticationFailure` (`"Token provider failed: ..."`); call `initialize` again to retry. Later on, it fails only the request that needed the token, and a map upload retries it like a dropped connection.
- The provider runs on a background thread and may suspend; a call that takes longer than 20 seconds fails. Requests made at the same time share one call.
- `MultiSetTokenProvider` is a suspending interface, so implement it in Kotlin.
- Calling `initialize` again with an equal provider keeps the token it gave, so build it once or make it a data class, as the samples' `TokenServerProvider` is.
- This keeps the secret off the device, but a token still carries your account's rights until it expires. Put your token endpoint behind your own sign-in.

---

## Error Handling

Session failures arrive as `MultiSetError`: `kind`, `message` (the server's own when it sent one), `statusCode`, `rawBody` and `isTransient`.

| Kind | Meaning |
|------|---------|
| `SERVER` | The API rejected the request; retrying without a config change won't help |
| `AUTH` | Credentials were rejected (401/403) |
| `NETWORK` | The API was unreachable |
| `TRANSIENT` | No pose, low confidence or no frame: expected to recur |

```kotlin
session.onLocalizationFailure = { error ->
    when (error.kind) {
        MultiSetError.Kind.AUTH      -> promptForCredentials()
        MultiSetError.Kind.NETWORK   -> showOfflineBanner()
        MultiSetError.Kind.SERVER    -> Log.e(TAG, "${error.statusCode}: ${error.message}")
        MultiSetError.Kind.TRANSIENT -> Unit
    }
}
```

---

## Results

| `LocalizationResult` | Description |
|----------------------|-------------|
| `mapCode`, `mapCodes` | The localized map, and every map code returned |
| `position`, `rotation` | Pose in AR world space (XYZ, quaternion XYZW) |
| `confidence` | Confidence score, if available |
| `geoCoordinates` | Latitude, longitude, altitude (with `includeGeoCoordinatesInResponse`) |
| `geoPose` | The whole geographic pose: position, orientation and frame, e.g. `WGS-84` / `Y-Up-ENU` |

The geographic fields need a geo-referenced map. Every localization response is logged as the server sent it, under the `MultiSetSDK` tag (`Localization response:`).

### Reading the GeoPose heading

`geoPose.quaternion` is the camera's orientation in the frame named by `geoPose.frameName`. In `Y-Up-ENU`, the service's default (+X east, +Y up, +Z true north), the camera looks along its own +Z. The compass heading is the bearing of that direction, clockwise from north:

```kotlin
val q = result.geoPose?.quaternion ?: return
val east = 2.0 * (q.x * q.z + q.w * q.y)
val north = 1.0 - 2.0 * (q.x * q.x + q.y * q.y)
val heading = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0   // 0 = north, 90 = east
```

An `ENU` GeoPose (+X east, +Y north, +Z up) carries a camera that looks along its -Z instead. The sample's `geopose/GeoPoseMath.compassHeading` reads both frames, and takes the heading from the top of the image when the camera is aimed at the floor or the sky. Do not read the heading as "yaw about Y": that is the same number only while the phone is level. The longitude needs no mirroring.

| `ObjectTrackingResult` | Description |
|------------------------|-------------|
| `objectCode`, `objectCodes` | The tracked object, and every object code returned |
| `position`, `rotation` | Object pose in AR world space |
| `confidence` | Confidence score |

---

## Advanced: Raw Stateless APIs

For direct control over frame selection, without a session:

```kotlin
val single:  LocalizationResult   = MultiSetSDK.localizeSingleFrame(frame)
val vps2:    LocalizationResult   = MultiSetSDK.localizeSingleFrame(frame, QueryMode.VPS2)
val multi:   LocalizationResult   = MultiSetSDK.localizeMultiFrame(listOf(f1, f2, f3, f4), QueryMode.VPS2)
val tracked: ObjectTrackingResult = MultiSetSDK.trackObjects(frame)
```

Each `suspend` call sends one request; scheduling, retry and gating are yours. The localization calls send the configured hints, and the GPS hint while `enableGeoHint` is on.

---

## Advanced: 3D Mesh Loading

```kotlin
val meshes = MultiSetSDK.meshRepository()

val map: MapMeshResult? = meshes.loadMapMesh(result.mapCode)    // map mesh and its pose
val meta = meshes.fetchObjectMeshMetadata(objectCode)          // object mesh
val bytes: ByteArray = meshes.downloadMesh(meta.fileUrl!!)
```

`MapMeshResult` carries `mapId`, the GLB `meshBytes`, and `localPosition` / `localRotation`. Place the mesh as a child of a node at the localization pose. For a MapSet member, the local pose is the member's offset from the set's origin, already in the right-handed AR frame.

If you reuse the sample's renderers (`ar/MeshRenderer`, `ar/ObjectMeshRenderer`):

- Call `MeshRenderer.onFrame(cameraWorldPosition)` every frame (`null` while not tracking) and `release()` on teardown; call `ObjectMeshRenderer.onFrame()` every frame.
- For object outlines, draw the camera feed first: `arSceneView.cameraStream.renderPriority = Renderable.RENDER_PRIORITY_FIRST`.
- The materials are compiled for Sceneform's Filament 1.57.1. After editing a `.mat` in `app/src/main/assets/materials/`, recompile it with `matc` from that release, using the command in the file's header.

---

## Map Scanning (Non-LiDAR)

The SDK records a new map from camera frames, plus depth when the device has it, packages it as `MappingData.zip`, keeps it as a draft and uploads it to your account. `MultiSetSDK.mapping` (`MultiSetMapping`) has the same shape as the iOS SDK's, and your runtime supplies the camera through `MultiSetMappingSource`.

`MultiSetSDK.mapping` needs `initialize()` to have run (it throws `IllegalStateException` before that); scanning needs no authentication, uploading does. `release()` drops the instance, deletes an unsaved scan and stops uploads in progress.

### The host side

```kotlin
interface MultiSetMappingSource {
    val depthSupported: Boolean                                  // the AR session runs a depth API
    val locationProvider: MappingLocationProvider? get() = null  // geo-tags the map
    fun startPreview()
    fun startRecording()
    fun pause()                                                  // the SDK has stopped using the source
    suspend fun acquireRGBFrame(): MappingRGBFrame?              // the latest frame's pose; null while not tracking
}

interface MappingRGBFrame {
    val position: Vec3                                           // raw AR camera pose
    val rotation: Quat
    val timestamp: Double                                        // AR frame clock, seconds
    val hasDepth: Boolean
    fun readImage(): MappingCameraImage?                         // that same frame's pixels; null skips it
}
```

```kotlin
mapping.reportTrackingSample(MappingTrackingSample(trackingState, position, timestamp, featurePointCount, pixelIntensity))
mapping.reportSourceInterrupted()      // from onPause, before super.onPause()
mapping.reportSourceFailure(message)   // the AR session failed
```

- **Pose first, pixels later.** The SDK calls `readImage()` on the main thread, straight after `acquireRGBFrame()`, and only for a frame that passes every check (tracking settled, 5 cm or 5° of movement, depth when the scan needs it). Return that frame's pixels, or `null` if the runtime has moved on. Build the image with `MappingCameraImage.copyOf(cameraImage, intrinsics, depthImage)`, and pass the raw pose and the image's own intrinsics.
- **Report one tracking sample per AR update.** Frames are taken only while the latest sample is `NORMAL`, and a tracking loss or pose jump deletes the frames from the second before it.
- **Depth is all-or-none.** When `depthSupported` is true, the scan waits up to 2 s for a frame with depth; if one arrives, every kept frame must carry depth, otherwise the scan is RGB-only.

### Running a scan

```kotlin
val mapping = MultiSetSDK.mapping
mapping.setSource(arMappingSource)
lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { mapping.scan.collect { render(it) } } }

recordButton.setOnClickListener { mapping.startScan() }
stopButton.setOnClickListener {
    lifecycleScope.launch {
        when (mapping.stopScan()) {
            MappingStopOutcome.PACKAGED -> showNameCard()
            MappingStopOutcome.DISCARDED_TOO_SHORT -> showTooShort()
            MappingStopOutcome.FAILED, MappingStopOutcome.NOT_RECORDING -> Unit   // scan.phase says why
        }
    }
}
saveButton.setOnClickListener { lifecycleScope.launch { mapping.saveDraft(name) } }   // at least 3 characters

override fun onResume() { super.onResume(); mapping.beginPreview() }
override fun onPause() { mapping.reportSourceInterrupted(); super.onPause() }
```

`MultiSetMapping` outlives the activity. In `onDestroy`, call `endPreview()` and `setSource(null)` only if the attached source is still this screen's, since a newer screen may have attached its own. Call `MultiSetMapping` from the main thread.

| `scan.phase` | What happens |
|--------------|--------------|
| `Idle` | Nothing is recording |
| `Preparing` | Checks for 1.5 GB of free space and waits up to 15 s for a `NORMAL` tracking sample |
| `Mapping` | Keeps a frame every 250 ms; stops by itself at 10 min |
| `Zipping` | Packages the scan (`packagingProgress` 0 to 1) |
| `Finished` | `MappingData.zip` is ready for `saveDraft(name)` |
| `Failed(failure)` | Nothing was kept; `failure.reason` and `failure.message` say why. `acknowledgeFailure()` returns to `Idle` |

A scan shorter than 10 s is discarded. An interruption ends a scan of 10 s or more as it stood and fails a shorter one; a scan never continues across an interruption. While recording, the state also reports saved, blurred and discarded frame counts, a motion warning and a tracking level for your guidance UI.

### Drafts and upload

```kotlin
combine(mapping.drafts, mapping.uploads, ::Pair).collect { (drafts, uploads) -> render(drafts, uploads) }

mapping.upload(draft.id)                 // also "Try again" and "Retry now"
mapping.deleteDraft(draft.id)            // cancels an upload in progress
mapping.dismissUploadFailure(draft.id)
override fun onResume() { super.onResume(); mapping.resumeUploads() }
```

`drafts` lists the saved scans not yet uploaded, newest first. `uploads` maps a draft id to its `MappingUploadState`:

| State | Meaning |
|-------|---------|
| *(no entry)* | Idle. A successful upload removes the draft and its files |
| `Preparing` / `Uploading(fraction)` | Getting an upload ticket / sending `MappingData.zip` |
| `Waiting(reason)` | Queued: a retry countdown, waiting for `resumeUploads()`, or `"Authenticate the SDK to upload"` |
| `Failed(message)` | Not retried on its own; `upload()` tries again |

- Network errors, HTTP 408, 429 and 5xx are retried 5 times (10 to 160 s apart); after that the draft waits for `resumeUploads()`. A retry re-uploads to the map the first attempt created, so no second map is made.
- Uploads use the SDK's token, from the client credentials or your `tokenProvider`. Refused credentials leave the draft waiting until the next successful authentication; a token provider that fails is retried like a network error.
- **Uploads run in your app's process.** There is no background service: if the process dies or `release()` is called, the queue survives and the upload restarts from the beginning after the next authentication. The SDK calls `resumeUploads()` after each successful `initialize()`; call it from `onResume` too.

Scans are stored under `Context.noBackupFilesDir/multiset/mapping/`, so Auto Backup never copies them:

```
DraftMapList.json
MapUploadQueue.json
MappingData/<session-id>/
├── RawData/
│   ├── CameraIntrinsics.json
│   ├── RGB/Rgb_<ts>.jpg            960×720 JPEG
│   ├── Depth/Depth_<ts>.exr        256×192 float metres (depth scans only)
│   └── Pose/CameraPose_<ts>.json
└── MappingData.zip
```

---

## Sample App (`:app`)

The AR screens are reference implementations to copy or replace. Their run-status UI is ported from the iOS SDK: a capture overlay with a frame count, a server-wait overlay, and a round status card for every run, background runs included. A return from the background hides the map, gizmo and outlines until they are re-aligned.

**Localization** (`MultiSetLocalizationActivity`):

```kotlin
startActivity(Intent(this, MultiSetLocalizationActivity::class.java).putExtra(
    MultiSetLocalizationActivity.EXTRA_LOCALIZATION_MODE,
    LocalizationMode.SINGLE_FRAME.name   // the default; or MULTI_FRAME
))
```

Single- or multi-frame with VPS-1 / VPS-2; automatic, manual and background localization; relocalization after a break; the map mesh with a radial reveal and a toggle (in a MapSet, each localized map adds its own mesh); false-positive rejection; the GPS hint.

**Object tracking** (`ObjectTrackingActivity`): pass codes with `EXTRA_OBJECT_CODES` or through `ObjectTrackingConfig`. Each tracked object gets an animated outline on its own anchor; Reset clears them and re-arms tracking.

**Map scanning:** a Mapping card on the home screen opens "Before you scan" tips (`MappingInstructionsActivity`), the landscape scan screen (`MapScanActivity`) with a timer, tracking guidance and live coverage points, and Draft Maps (`DraftMapsActivity`) with thumbnails and upload status. `mapping/ArMappingSource` implements `MultiSetMappingSource` over ARCore.

**Geo Pose Map View** (`ui/geopose/GeoPoseMapActivity`): the localization session over the camera, and under it an OpenStreetMap sheet ([osmdroid](https://github.com/osmdroid/osmdroid), no API key) that plots every fix's GeoPose. The latest fix is an arrow along its compass heading, coloured by confidence; earlier fixes are dots on a dashed line; the device GPS is the blue track with its accuracy circle. A card gives the heading, latitude and longitude, altitude, confidence and map code. It uses every Localization setting and always asks for the GeoPose, whatever **Include Geo Response** says. For a map that is not geo-referenced it says the response has no GeoPose. The logic to copy is in `geopose/`; osmdroid is a dependency of the sample, not of the SDK.

---

## XREAL Aura sample (`:xreal-aura-vps`)

The same SDK on Android XR glasses, installed as `com.multiset.aura`. Jetpack XR gives no camera image or intrinsics, so `frame/AuraFrameSource` builds each `CameraFrame` from a Camera2 still (the whole frame, sent at 1280 × 720 with `imageLongSide(1280)`), the head pose at mid-exposure and a calibration profile. SceneCore anchors the result in the room.

| Area | What the sample does |
|------|----------------------|
| Map / MapSet localization | Single- and multi-frame; the map mesh with a radial reveal, a gizmo and a mesh toggle; in a MapSet each localized map adds its own mesh |
| Object tracking | An outline on each tracked object, one anchor per object |
| Settings | Every session option, persisted, and a Map / MapSet switch when both codes are set |

Map scanning and the GPS hint are not part of this sample.

- **Requires** an Android XR device with a world-facing camera, such as XREAL Aura, on Android 14+. Elsewhere it shows a "needs Android XR" message.
- **Credentials** come from the same `multiset.properties`, `MULTISET_TOKEN_SERVER_URL` included; rebuild after editing it.
- **Permissions:** `CAMERA`, `android.permission.HEAD_TRACKING` and `android.permission.SCENE_UNDERSTANDING_COARSE`, each asked from a card in the panel.
- **Full Space** is needed for 3D content; in Home Space the sample asks you to switch. Jetpack XR is pinned (`compose` 1.0.0-beta02; `runtime`, `scenecore`, `arcore` 1.0.0; `extensions-xr` 1.4.0).
- **Recovery:** the sample re-localizes and re-tracks itself when head tracking resumes, so it turns off the sessions' own `relocalization` and `restartTracking`.

```bash
./gradlew :xreal-aura-vps:installDebug      # glasses or puck connected over adb
```

---

## Permissions

| Permission | Purpose | Declared by |
|------------|---------|-------------|
| `INTERNET` | API communication | SDK (merged in) |
| `CAMERA` | AR camera | Your app |
| `ACCESS_FINE_LOCATION` | GPS hint, and geo-tagging scanned maps (optional) | Your app |
| `ACCESS_COARSE_LOCATION` | Geo-tagging a scan with approximate location only (optional) | Your app |

`:app` also requires the `android.hardware.camera.ar` feature.

---

## Building

```bash
./gradlew :app:installDebug                  # ARCore phone
./gradlew :xreal-aura-vps:installDebug       # Android XR glasses
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease :xreal-aura-vps:assembleRelease
```

The SDK ships prebuilt as `app/libs/multiset-sdk.aar`, and both samples build against it. To move to a newer SDK, replace that file and rebuild.

---

## Support

- **Documentation:** https://developer.multiset.ai/docs
- **Developer Portal:** https://developer.multiset.ai
- **Email:** support@multiset.ai

---

## License

Copyright (c) 2026 MultiSet AI. All rights reserved.

Licensed under the MultiSet License. You may not use this file except in compliance with the License. Redistribution in source or binary forms must retain this notice.
