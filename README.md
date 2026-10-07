# MultiSet Android SDK

Visual Positioning System (VPS) SDK for Android applications. Achieve centimeter-level indoor localization and real-time object tracking using computer vision and AR technology.

---

## Overview

The MultiSet SDK enables visual positioning and object tracking in your Android applications. It supports both single map and map set localization, providing accurate 6DOF pose estimation with AR visualization capabilities — and a dedicated Object Tracking mode for recognizing and tracking pre-registered physical objects in AR.

This repository ships the SDK as a prebuilt **platform-agnostic** library plus a reference AR sample app:

| Item | Contents |
|------|----------|
| `app/libs/multiset-sdk.aar` | The SDK — auth, networking, capture scheduling, pose math, mesh loading. **Zero ARCore/Sceneform dependencies.** |
| `:app` | ARCore + Sceneform sample app — AR activities, rendering, settings UI. Drop-in reference for your own integration. |

The SDK never touches an AR runtime. Your app converts each camera frame into a plain `CameraFrame` and hands it to the SDK through the `FrameSource` interface, so the same library runs on ARCore or any other Android XR runtime.

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
- **Animated outline mesh** rendered over tracked objects
- **Background object tracking** with configurable retry intervals
- **Runtime-agnostic frame source** — integrate on any Android XR platform, not just ARCore
- **Query mode selection** (VPS-1 / VPS-2) for single-frame localization
- **False-positive rejection** — discards fixes that contradict the device's own AR trajectory
- **Automatic token refresh** — short-lived M2M tokens are renewed transparently
- **Typed failures** — `MultiSetError` classifies server / auth / network / transient errors
- **Configurable API host** for staging and on-premise deployments

---

## What's New in 2.0.0

| Change | Notes |
|--------|-------|
| **Pose consistency gate is on by default** | `poseConsistencyCheck` now defaults to `true`. Instead of a single reference fix, the SDK keeps an **anchor** and lets agreeing fixes from separated viewpoints out-vote it, so a wrong first fix can be corrected without a manual reset. See [Pose Consistency](#pose-consistency-false-positive-check). |
| **New callbacks** | `onLocalizationCorrected(from, to, trust)` fires when the anchor moves and `onAnchorTrustChanged(trust)` reports its standing (`PROVISIONAL` / `CORROBORATED` / `TRUSTED`, also readable via `MultiSetSDK.anchorTrust`). Both have default no-op implementations. |
| **Two flat, independent rejection limits** | The gate no longer scales tolerance with anchor age. `poseConsistencyThreshold` (distance, default 4 m, 1.5-15) and the new `poseConsistencyYawThreshold` (heading, default 25°, 10-60) are each applied as-is — either one alone can force a contest. |
| **Richer `FalsePositiveInfo`** | Adds `yawDeltaDeg`, `thresholdDegrees`, `disagreement` (`DISTANCE` / `HEADING` / `BOTH`), `cause` (`INSUFFICIENT_VIEWPOINTS` / `ANCHOR_HELD_VOTE` / `INLIER_MARGIN`), `challengerSupport` / `requiredSupport` / `incumbentSupport`, `trust` and a `rejection` kind (`FALSE_POSITIVE`, `REESTABLISHING`, `SUSPECTED_ALIASING`). |
| **Tracking signals** | New `session.notifyWorldOriginReset()` for ARCore `BAD_STATE` / `STOPPED`; `notifyTrackingInterrupted()` stays for recoverable losses. The sample app maps both in `ar/ArTrackingSignals.kt`. A stale anchor's next agreeing fix is now **adopted outright** rather than refined against a reference the gate has stopped trusting. |
| **`CameraFrame.timestampSeconds`** | Optional monotonic capture time (ARCore `frame.timestamp / 1e9`), used for window TTL, pruning and candidate ageing — it no longer scales the gate's tolerance with anchor age. Defaults to the JVM monotonic clock, so existing `FrameSource` implementations keep compiling. |
| **Session resets** | `MultiSetSDK.resetLocalizationSession()` is new — the only exit from a bad *first* fix, since that fix bootstraps the anchor unchecked. `resetPoseConsistencyReference()` stays for re-bootstrapping the gate when the anchor itself is fine. |
| **`worldOriginResetHandler`** | New host boundary (`WorldOriginResetHandler`) for rebuilding the runtime's world origin on a genuine reset. Leaving it unset is supported. |
| **New config** | `poseConsistencyTuning` (`GateTuning` overrides — its shape changed, see the upgrade notes below) and `mapAliasingRisk` (per-map viewpoint multiplier for repetitive spaces). |
| **Escalation** | Two consecutive rejections trigger one multi-frame request (5 s cooldown) to break the tie faster. |
| **Sample app: mesh rendering** | The map mesh's radial reveal now stays centred where you localized instead of drifting with the camera, and covers every part of the GLB. The tracked-object outline is now a glowing rim around the object's edges rather than a fill over the whole mesh. See [Advanced: 3D Mesh Loading](#advanced-3d-mesh-loading) if you copied the renderers into your app. |

**Upgrading from 1.16.0 is source-breaking.** See [Upgrading to 2.0.0](#upgrading-to-200) below before you rebuild.

---

## What's New in 1.16.0

| Change | Notes |
|--------|-------|
| **AR-free SDK** | ARCore and Sceneform are gone from the SDK entirely; they now live only in the sample app. The AAR runs on any Android XR runtime that can supply a `CameraFrame`. |
| **False-positive rejection** | Localization fixes that contradict the device's own AR trajectory are discarded and reported via `onLocalizationFalsePositive` instead of moving your content to the wrong place. |
| **Configurable API host** | `MULTISET_BASE_URL` / `MultiSetSDKConfig.baseUrl` point the SDK at staging or on-premise deployments. Empty means production. |
| **Query mode** | `QueryMode.VPS1` / `VPS2` for single-frame localization. Multi-frame always runs VPS-1. |
| **Typed failures** | Session failure callbacks now receive `MultiSetError` (`SERVER` / `AUTH` / `NETWORK` / `TRANSIENT`) rather than a bare string. |
| **Automatic token refresh** | Short-lived M2M tokens are renewed transparently, with a single retry on HTTP 401. |
| **Bug fixes** | `stop()` on both sessions now cancels requests already in flight, so a late response can no longer re-apply a pose after you tear the scene down. Map-mesh rendering no longer reuses a previous map's geometry after switching maps. |

**Upgrading from 1.11.x:** the sample app's package changed from `com.multiset.sdk.android` to
`com.multiset.xr`, and the SDK's own API moved to session objects (`MultiSetSDK.localizationSession(...)`)
fed by a `FrameSource` you implement. See *The Platform-Agnostic Boundary* below.

---

## Requirements

| Requirement | Minimum |
|-------------|---------|
| Android API Level | 28 (Android 9.0) |
| Target SDK | 36 |
| Java Version | 17 |
| Kotlin Version | 2.2.0+ |
| Gradle / AGP | 8.11.1 / 8.10.0 |
| Device | ARCore-supported with camera (sample app only) |

The SDK itself needs only a camera image, a pose, and camera intrinsics — ARCore is a requirement of the sample app, not of the SDK.

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

| Component | Description |
|-----------|-------------|
| `multiset-sdk` (`.aar`) | Core SDK library — AR-free, unit-testable on plain JVM |
| `MultiSetSDK` | Singleton entry point (initialize, sessions, raw APIs, mesh repository) |
| `MultiSetSDKConfig` | Configuration data class with a Java-friendly `Builder` |
| `MultiSetSDKCallback` | SDK-wide event callbacks |
| `FrameSource` / `CameraFrame` | The platform-agnostic boundary your runtime implements |
| Sample App | Reference ARCore + Sceneform implementation with AR activities |
| `LocalizationConfig.kt` / `ObjectTrackingConfig.kt` | Sample-app settings singletons |

---

## Integration Steps

### Step 1: Add the AAR

Copy `multiset-sdk.aar` into your app module's `libs/` directory and depend on it:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/multiset-sdk.aar"))
}
```

### Step 2: Configure Dependencies

> **An AAR referenced as a file dependency carries no dependency metadata.** Unlike a Maven
> coordinate, nothing is pulled in transitively — you must declare everything the SDK uses
> yourself, or it will compile and then fail at runtime with `NoClassDefFoundError`.

The SDK requires these:

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
implementation("com.google.code.gson:gson:2.10.1")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
implementation("com.google.mlkit:vision-common:17.3.0")
implementation("androidx.core:core-ktx:1.16.0")
```

Rendering AR — as the sample app does — additionally requires:

- ARCore 1.46.0 (Sceneform pulls this up to 1.50.0 at resolution)
- Sceneform 1.24.6 (RGregat fork with 16KB page size support)

Neither is needed if you drive the SDK from a non-ARCore runtime.

### Step 3: Add JitPack Repository

The Sceneform fork is published on JitPack, which must be declared in your project settings. Only apps rendering with Sceneform need this — the core SDK does not.

### Step 4: Configure Packaging Options

Enable legacy JNI packaging (`packaging.jniLibs.useLegacyPackaging = true`) for 16KB page size compatibility with Sceneform's native libraries.

---

## SDK Configuration

### Credentials Setup

Credentials are never hand-edited into Gradle files. Copy the template to a properties file at the **repository root** and fill it in:

```bash
cp multiset.properties.template multiset.properties
```

**Keep `multiset.properties` out of version control.** If it is not yet ignored in your checkout, add it before filling in any real values:

```bash
echo "multiset.properties" >> .gitignore
git rm --cached multiset.properties      # if it is already tracked
```

The file is read at **build time** by `app/build.gradle.kts` and exposed to the app as `BuildConfig` fields, which `MultiSetSDKInit` turns into a `MultiSetSDKConfig`. If the file is missing, every value defaults to empty: the build still succeeds, but the app reports that the SDK is not configured.

**The file, as shipped by the template:**

```properties
# API Host (Optional)
# Leave empty to use the production host: https://api.multiset.ai
MULTISET_BASE_URL=

# Authentication Credentials (Required)
MULTISET_CLIENT_ID=
MULTISET_CLIENT_SECRET=

# Map Configuration (Provide one of the following)
MULTISET_MAP_CODE=
MULTISET_MAP_SET_CODE=

# Object Tracking Configuration (comma-separated object codes, max 10)
# example OBJ_001, OBJ_002 ...
MULTISET_OBJECT_CODES=
```

| Property | Description | Required |
|----------|-------------|----------|
| `MULTISET_BASE_URL` | API host override; empty = production | No |
| `MULTISET_CLIENT_ID` | Your client identifier | Yes |
| `MULTISET_CLIENT_SECRET` | Your secret key | Yes |
| `MULTISET_MAP_CODE` | Single map identifier | One of these |
| `MULTISET_MAP_SET_CODE` | Map set identifier | is required |
| `MULTISET_OBJECT_CODES` | Comma-separated object codes | For object tracking |

**A filled-in example:**

```properties
MULTISET_BASE_URL=
MULTISET_CLIENT_ID=your_client_id
MULTISET_CLIENT_SECRET=your_client_secret
MULTISET_MAP_CODE=your_map_code
MULTISET_MAP_SET_CODE=
MULTISET_OBJECT_CODES=OBJ_001, OBJ_002
```

#### Updating a value

Edit the line in place — `KEY=value`, no quotes and no trailing comment on the same line. Because the values are baked into `BuildConfig` at build time, **rebuild after every edit**; re-launching the installed app is not enough:

```bash
./gradlew :app:installDebug
```

In Android Studio, use *Sync Project with Gradle Files* (or just Run) after editing.

#### Credentials — `MULTISET_CLIENT_ID` / `MULTISET_CLIENT_SECRET`

Both are required. Get them from https://developer.multiset.ai/credentials and paste the raw values:

```properties
MULTISET_CLIENT_ID=ms_client_abc123
MULTISET_CLIENT_SECRET=ms_secret_xyz789
```

If either is left empty the app skips initialization entirely and reports the SDK as unavailable, rather than failing later at the first API call.

#### Map — `MULTISET_MAP_CODE` *or* `MULTISET_MAP_SET_CODE`

Fill exactly one and leave the other empty. A single map:

```properties
MULTISET_MAP_CODE=your_map_code
MULTISET_MAP_SET_CODE=
```

A map set (several maps searched together):

```properties
MULTISET_MAP_CODE=
MULTISET_MAP_SET_CODE=your_map_set_code
```

**If both are filled, `MULTISET_MAP_CODE` wins** and the map set code is ignored — so clear the one you are not using rather than leaving a stale value behind. Whichever is active is attached to every localization request; omitting both means localization requests are rejected with HTTP 400.

Map set localization also unlocks `hintMapCodes`, which narrows the search to a subset of the set (see [Localization Hints](#localization-hints)).

#### Object codes — `MULTISET_OBJECT_CODES`

A comma-separated list, **max 10**. Surrounding whitespace is trimmed and empty entries are dropped, so both of these are equivalent:

```properties
MULTISET_OBJECT_CODES=OBJ_001,OBJ_002
MULTISET_OBJECT_CODES=OBJ_001, OBJ_002
```

Leave it empty if you are not using object tracking. Object codes are independent of the map settings — you may set object codes *and* a map code in the same file to use both features from one build. Supplying more than 10 codes fails configuration validation.

At least one of `MULTISET_MAP_CODE`, `MULTISET_MAP_SET_CODE`, or `MULTISET_OBJECT_CODES` must be present; with all three empty the app will not initialize the SDK.

#### API host — `MULTISET_BASE_URL`

Leave it empty to use production (`https://api.multiset.ai`). Set it to point the SDK at a staging server or a machine on your LAN — every endpoint is derived from this one host:

```properties
MULTISET_BASE_URL=https://staging.multiset.ai
```

Trailing slashes and surrounding whitespace are trimmed; the value must start with `http://` or `https://`, or configuration fails at startup. Plain `http://` hosts only work in **debug** builds, where the sample app's `app/src/debug/AndroidManifest.xml` enables cleartext traffic — release builds keep it disabled.

The sample app's Settings dialog shows the active host and whether it is Production or Custom. That card is read-only: the host is fixed at build time from `MULTISET_BASE_URL`, because the SDK reads it once, before authenticating.

### Initialization

```kotlin
val config = MultiSetSDKConfig.Builder(clientId, clientSecret)
    .mapCode("your_map_code")            // or .mapSetCode(…)
    .localizationMode(LocalizationMode.MULTI_FRAME)
    .poseConsistencyCheck(true)
    .poseConsistencyYawThreshold(25f)
    .build()

MultiSetSDK.initialize(applicationContext, config, callback)
```

`MultiSetSDKConfig` validates on construction — blank credentials, a missing map/object identifier, `numberOfFrames` outside 4–6, `imageQuality` outside 1–100, `confidenceThreshold` outside 0–1, `poseConsistencyThreshold` outside 1.5–15 m, `poseConsistencyYawThreshold` outside 10–60°, more than 10 object codes, `hintRadius` outside 1–100, or a malformed `baseUrl` all throw immediately.

---

## The Platform-Agnostic Boundary

To run the SDK on any XR runtime, implement one interface:

```kotlin
interface FrameSource {
    suspend fun acquire(): CameraFrame?   // return null when no frame is ready
}
```

```kotlin
data class CameraFrame(
    val bitmap: Bitmap?,                // RGB camera image (null → frame skipped)
    val cameraPosition: Vec3,           // world position (x, y, z)
    val cameraRotation: Quat,           // world rotation (x, y, z, w)
    val intrinsics: CameraIntrinsics,   // fx, fy, cx, cy, imageWidth, imageHeight
    val orientation: DeviceOrientation  // PORTRAIT or LANDSCAPE
)
```

Pass the **raw, uncorrected** camera pose. Portrait orientation correction — bitmap rotation, quaternion correction and intrinsic axis swap — happens exactly once inside the SDK; pre-correcting produces a double rotation.

The SDK owns everything else: capture scheduling, retry, background re-localization, confidence gating, JPEG encoding, networking, and pose math. The sample app's `ar/ArFrameSource` is the only AR-aware class in the project and shows the minimal ARCore glue.

**Capture is gated on tracking.** Starting a session before the runtime reports a tracking camera yields "Failed to capture frame"; the sample activities defer `session.start()` until tracking begins.

---

## SDK Callbacks

Implement the `MultiSetSDKCallback` interface to receive SDK events:

| Callback | Description |
|----------|-------------|
| `onSDKReady()` | SDK initialized and ready |
| `onAuthenticationSuccess()` | Authentication completed successfully |
| `onAuthenticationFailure(error: String)` | Authentication failed with error |
| `onLocalizationSuccess(result: LocalizationResult)` | Localization succeeded with result |
| `onLocalizationFailure(error: String)` | Localization failed with error |
| `onLocalizationFalsePositive(info: FalsePositiveInfo)` | A fix was rejected as inconsistent with the AR trajectory *(optional)* |
| `onLocalizationCorrected(from: AnchorPose, to: AnchorPose, trust: AnchorTrust)` | Agreeing fixes out-voted the anchor and the map frame moved; re-parent your own world-space content *(optional)* |
| `onAnchorTrustChanged(trust: AnchorTrust)` | The corroboration behind the applied anchor changed *(optional)* |
| `onTrackingStateChanged(state: TrackingState)` | AR tracking state changed |
| `onObjectTrackingSuccess(result: ObjectTrackingResult)` | Object tracked successfully *(optional)* |
| `onObjectTrackingFailure(error: String)` | Object tracking failed with error *(optional)* |

Callbacks marked optional have default no-op implementations — override only what you need.

### Example

```kotlin
class MainActivity : AppCompatActivity(), MultiSetSDKCallback {

    override fun onSDKReady() { /* SDK initialized */ }

    override fun onAuthenticationSuccess() {
        // SDK is ready — enable localization and tracking buttons
    }

    override fun onAuthenticationFailure(error: String) {
        Log.e(TAG, "Auth failed: $error")
    }

    override fun onLocalizationSuccess(result: LocalizationResult) {
        Log.d(TAG, "Position: ${result.position.joinToString()}")
        Log.d(TAG, "Confidence: ${result.confidence}")
    }

    override fun onLocalizationFailure(error: String) { /* handle */ }

    override fun onLocalizationFalsePositive(info: FalsePositiveInfo) {
        Log.w(TAG, info.summary)
    }

    override fun onTrackingStateChanged(state: TrackingState) { }

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

## Sessions

Sessions run the whole capture loop for you — scheduling, retry, background re-tracking, confidence gating and pose math — pulling frames from your `FrameSource`.

**Localization:**

```kotlin
val session = MultiSetSDK.localizationSession(frameSource)   // mode defaults to config
session.queryMode = QueryMode.VPS2                           // single-frame only
session.poseConsistencyCheck = true
session.poseConsistencyYawThreshold = 25f
session.onLocalizationSuccess = { result -> /* result.position, result.rotation */ }
session.onLocalizationFailure = { error -> /* MultiSetError */ }
session.onLocalizationFalsePositive = { info -> /* rejected fix */ }
session.onLocalizationCorrected = { from, to, trust -> /* anchor moved */ }
session.onAnchorTrustChanged = { trust -> /* PROVISIONAL / CORROBORATED / TRUSTED */ }
session.start()
// …
session.stop()
```

**Object tracking:**

```kotlin
val session = MultiSetSDK.objectTrackingSession(frameSource)  // codes default to config
session.onTrackingSuccess = { result -> /* result.objectCode, result.position */ }
session.onTrackingFailure = { error -> /* MultiSetError */ }
session.start()
session.stop()
```

Session failure lambdas receive a typed `MultiSetError`, unlike the SDK-wide callback interface, which reports a `String`.

When the AR runtime loses tracking, tell the localization session so the pose consistency gate can judge the next fix correctly — see [Pose Consistency](#pose-consistency-false-positive-check):

```kotlin
session.notifyTrackingInterrupted()   // recoverable loss
session.notifyWorldOriginReset()      // world origin no longer meaningful
```

---

## Localization Configuration

The sample app exposes these settings through the `LocalizationConfig` singleton (persisted via `ConfigStore`) and its Settings dialog. When integrating directly, the same fields exist on `MultiSetSDKConfig` and on `LocalizationSession`.

### Localization Behavior

| Setting | Description | Default |
|---------|-------------|---------|
| Auto Localize | Start localization automatically when AR session begins | true |
| Background Localization | Continue localizing after first success | true |
| Background Interval | Seconds between background localizations (15-180) | 30 |
| Relocalization | Re-localize when tracking is lost | true |
| First Until Success | Keep retrying until first localization succeeds | true |

`firstLocalizationUntilSuccess` swallows only **transient** failures. A rejected request — for example HTTP 400 from a malformed map code — surfaces immediately instead of retrying forever.

### Multi-Frame Capture

| Setting | Description | Default |
|---------|-------------|---------|
| Number of Frames | Frames to capture for multi-frame mode (4-6) | 4 |
| Capture Interval | Milliseconds between frame captures (100-1000) | 500 |

### Query Mode

| Setting | Description | Default |
|---------|-------------|---------|
| `queryMode` | VPS engine for single-frame localization: `VPS1` or `VPS2` | `VPS1` |

Query mode applies to **single-frame localization only** — multi-frame always runs VPS-1. The sample app shows the dropdown only in single-frame mode.

### Confidence Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Confidence Check | Reject localizations below threshold | false |
| Confidence Threshold | Minimum confidence score (0.0-1.0) | 0.3 |

### Pose Consistency (False-Positive Check)

| Setting | Description | Default |
|---------|-------------|---------|
| `poseConsistencyCheck` | Reject fixes that contradict the device's AR trajectory | **true** |
| `poseConsistencyThreshold` | Flat distance limit from the anchor, in metres (1.5-15) | 4 |
| `poseConsistencyYawThreshold` | Flat heading limit from the anchor, in degrees (10-60) | 25 |
| `poseConsistencyTuning` | `GateTuning` overrides; `null` selects the shipped values | `null` |
| `mapAliasingRisk` | Per-map multiplier on the viewpoints required to move the anchor, e.g. `mapOf("LOBBY_L2" to 2f)` for repeated structure | empty |

#### Upgrading to 2.0.0

- **`poseConsistencyThreshold` narrowed from 3-30 m to 1.5-15 m.** An existing `.poseConsistencyThreshold(20f)` now throws `IllegalArgumentException` at config construction — pick a value inside the new range.
- **`GateTuning` dropped `rejectionBaseM`, `driftRateMPerSec` and `rejectionMaxM`.** Code built on `poseConsistencyTuning` fails to compile. Migrate `rejectionBaseM`/`rejectionMaxM` to the single flat `rejectionDistanceM`; `driftRateMPerSec` has no replacement — the tolerance no longer scales with time.
- **`FalsePositiveInfo` and `GateDiagnostics` gained constructor parameters mid-list**, so positional `componentN` access and `copy()` calls shift — source-breaking for host code that constructs them directly (tests, fakes).
- **The gate now defaults on.** Hosts that never set `poseConsistencyCheck` will start seeing `onLocalizationFalsePositive` and rejected fixes; pass `poseConsistencyCheck = false` to keep the pre-gate behaviour.

Within one AR session every fix measures the same map-to-session transform, so the tracker's own trajectory is ground truth. The first response bootstraps the **anchor** — a multi-frame response arrives already fused from several viewpoints and bootstraps at `AnchorTrust.TRUSTED`, a single frame at `PROVISIONAL`. Every later response is a challenger:

- **Agrees** (within `poseConsistencyThreshold` of distance *and* `poseConsistencyYawThreshold` of yaw — two independent, flat limits; either one alone can force a contest): the anchor is **refined** to the geometric median of its recent agreeing fixes rather than replaced, so slow directional drift cannot walk the map away with a clean log.
- **Contests**: a vote decides. Support is counted in **distinct query viewpoints** (camera positions 1.5 m or headings 20° apart) — repeating a fix without moving adds nothing, and a multi-frame response counts for at most 2. A trusted anchor needs 3 viewpoints to displace, a corroborated one 2, a provisional one 1; ties go to the anchor. On displacement `onLocalizationCorrected(from, to, trust)` fires before the `onLocalizationSuccess` carrying the new pose.
- **Rejected**: `onLocalizationFalsePositive` fires and the scene is untouched — neither success nor failure is reported:

| `FalsePositiveInfo` field | Description |
|---------------------------|-------------|
| `jumpMeters`, `yawDeltaDeg` | How far the discarded response placed the map from the anchor |
| `thresholdMeters` | The distance limit in force — always exactly `poseConsistencyThreshold` |
| `thresholdDegrees` | The heading limit in force — always exactly `poseConsistencyYawThreshold`, independent of `thresholdMeters` |
| `disagreement` | `DISTANCE` / `HEADING` / `BOTH` — which limit forced the contest |
| `cause` | `INSUFFICIENT_VIEWPOINTS` / `ANCHOR_HELD_VOTE` / `INLIER_MARGIN` — why the contest was lost. Only `INSUFFICIENT_VIEWPOINTS` means "move and ask again"; check it before comparing `challengerSupport` to `requiredSupport` — on `ANCHOR_HELD_VOTE` the viewpoint count was never the disqualifier |
| `challengerSupport`, `requiredSupport`, `incumbentSupport` | Viewpoints behind the response, needed to move the anchor, and behind the anchor |
| `consecutiveCount` | Consecutive rejections since the last accepted fix |
| `trust` | `AnchorTrust` of the anchor that was kept |
| `rejection` | `FALSE_POSITIVE`, `REESTABLISHING` (retried quietly), or `SUSPECTED_ALIASING` (enough viewpoints but the server-reported inlier margin was not cleared — re-localizing will not help) |
| `mapCodes`, `confidence`, `reason`, `summary` | The rejected response's identity and a log-ready line |

After two consecutive rejections the session escalates to **one** multi-frame request (5 s cooldown, once per interval, never for `SUSPECTED_ALIASING`). `onAnchorTrustChanged` reports the anchor's standing; `MultiSetSDK.anchorTrust` reads it.

Tell the session about the AR runtime's tracking signals. A recoverable loss (excessive motion, poor features or light) keeps the anchor but marks it stale — its next agreeing fix is **adopted outright** rather than refined, since refining against a reference the gate has stopped believing would land that fix at the midpoint; a world-origin reset (ARCore `TrackingFailureReason.BAD_STATE`, `TrackingState.STOPPED`) discards every offset measured in the old frame:

```kotlin
session.notifyTrackingInterrupted()   // recoverable loss — anchor kept, stale
session.notifyWorldOriginReset()      // BAD_STATE / STOPPED — everything dropped
```

`CameraFrame.timestampSeconds` is the gate's only clock; it drives window TTL, pruning and candidate ageing — it no longer scales the rejection tolerance. Supply the runtime's monotonic capture time (ARCore `frame.timestamp / 1e9`).

```kotlin
MultiSetSDK.resetPoseConsistencyReference()   // the anchor is fine, re-bootstrap the reference
MultiSetSDK.resetLocalizationSession()        // the map itself is misplaced, start over
```

`resetLocalizationSession()` is the only exit from a bad **first** fix: that fix bootstraps the anchor unchecked and, from a multi-frame request, as `TRUSTED`, after which every *correct* response is the one reported through `onLocalizationFalsePositive`. Nothing the gate can observe tells that apart from the healthy case, so offer it from your false-positive UI, not just a toolbar. Put your own scene back with it — remove the map mesh, and move your anchor node to identity and hide it.

ARCore has no equivalent of ARKit's `.resetTracking` (`pause()`/`resume()` deliberately relocalize against the *same* world frame), so a genuinely fresh origin means rebuilding the `Session`. Implement `session.worldOriginResetHandler` where you own it, ending with `notifyWorldOriginReset()`. **Leaving it unset is supported** — the sample app deliberately does so — the SDK's state resets, the world frame does not.

With `poseConsistencyCheck = false` behaviour is identical to the pre-gate SDK: the raw response is applied and no gate callback fires.

### GPS Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Enable Geo Hint | Send GPS coordinates to improve localization | false |
| Include Geo Response | Include geo coordinates in result | false |

**Note:** Geo hint requires location permissions and a geo-referenced map.

### Localization Hints

Optional hints that narrow the localization search. All are disabled by default — set them before launching the AR activity.

| Setting | Description | Default |
|---------|-------------|---------|
| `hintMapCodes` | Subset of map codes within a map set to restrict localization to (map set localization only) | empty |
| `hintPosition` | Approximate position hint in `"x,y,z"` format | empty |
| `hintFloorHeight` | Floor/ceiling height constraint in `"floor,ceiling"` format, e.g. `"0,5"` | empty |
| `hintRadius` | Search radius in meters for geo spatial filtering, applied when a geo or position hint is provided (1-100) | 25 |
| `use2DFiltering` | Skip altitude (Y-axis) in geo hint filtering, using only horizontal distance | false |

```kotlin
LocalizationConfig.hintMapCodes = listOf("MAP_CODE_1", "MAP_CODE_2")
LocalizationConfig.hintPosition = "12.5,0.0,-3.2"
LocalizationConfig.hintFloorHeight = "0,5"
LocalizationConfig.hintRadius = 25
LocalizationConfig.use2DFiltering = false
```

`hintMapCodes` only applies to map set localization; it is ignored for single-map localization.

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

The sample app provides an `ObjectTrackingConfig` singleton to customize tracking behavior. Configure these settings before launching `ObjectTrackingActivity`.

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
ObjectTrackingConfig.objectCodes = arrayOf("OBJ_XXXXXXYYY", "OBJ_XXXXXXZZZ")
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

## Authentication & Token Lifetime

Authentication is handled for you. The live M2M token is a short-lived (5-minute) JWT, and the SDK refreshes it transparently:

- Every authenticated request asks for a current token, so no token is ever held by value.
- Expiry is read from the API response, falling back to the JWT's own `exp` claim; an unreadable expiry is treated as already expired.
- Renewal happens ahead of expiry, with the lead time adapted to the token's actual lifetime.
- Concurrent callers share a single re-authentication rather than stampeding the token endpoint.
- A request rejected with **HTTP 401** is retried once with a fresh token; a recovered request reports no failure at all.

Hosts that call the MultiSet API themselves can borrow the same machinery via `MultiSetSDK.currentToken()`.

---

## Error Handling

Session failures are delivered as `MultiSetError` rather than free-form strings:

| Field | Description |
|-------|-------------|
| `kind` | `SERVER`, `AUTH`, `NETWORK`, or `TRANSIENT` |
| `message` | Human-readable text, preferring the server's own `message` field |
| `statusCode` | HTTP status code when the failure came from the API |
| `rawBody` | Unparsed API error body, for logging |
| `isTransient` | True for failures a retry may fix on its own |

| Kind | Meaning |
|------|---------|
| `SERVER` | The API rejected the request; retrying without a config change won't help |
| `AUTH` | Credentials were rejected (401/403) |
| `NETWORK` | The API was unreachable |
| `TRANSIENT` | Scan-time failure (no pose, low confidence, frame capture) — expected to recur |

```kotlin
session.onLocalizationFailure = { error ->
    when (error.kind) {
        MultiSetError.Kind.AUTH      -> promptForCredentials()
        MultiSetError.Kind.NETWORK   -> showOfflineBanner()
        MultiSetError.Kind.SERVER    -> Log.e(TAG, "${error.statusCode}: ${error.message}")
        MultiSetError.Kind.TRANSIENT -> Unit   // keep scanning
    }
}
```

---

## Localization Result

Successful localization provides a `LocalizationResult` object via `onLocalizationSuccess`:

| Field | Description |
|-------|-------------|
| `mapCode` | Code of the localized map |
| `mapCodes` | List of all map codes returned |
| `position` | XYZ coordinates in AR world space |
| `rotation` | Quaternion (XYZW) orientation |
| `confidence` | Localization confidence score (if available) |
| `geoCoordinates` | Latitude, longitude, altitude (if requested) |

---

## Object Tracking Result

Successful object tracking provides an `ObjectTrackingResult` object via `onObjectTrackingSuccess`:

| Field | Description |
|-------|-------------|
| `objectCode` | Code of the tracked object |
| `objectCodes` | List of all object codes returned |
| `position` | XYZ coordinates of the object in AR world space |
| `rotation` | Quaternion (XYZW) orientation of the object |
| `confidence` | Tracking confidence score |

---

## Advanced: Raw Stateless APIs

When you want direct control over frame selection instead of a managed session:

```kotlin
val single:  LocalizationResult   = MultiSetSDK.localizeSingleFrame(frame)
val vps2:    LocalizationResult   = MultiSetSDK.localizeSingleFrame(frame, QueryMode.VPS2)
val multi:   LocalizationResult   = MultiSetSDK.localizeMultiFrame(listOf(f1, f2, f3, f4))
val tracked: ObjectTrackingResult = MultiSetSDK.trackObjects(frame)
```

These are `suspend` functions that perform one request and return; scheduling, retry and gating are yours to manage.

---

## Advanced: 3D Mesh Loading

Mesh download is a two-step signed-URL flow — the `meshLink` returned by the API is a storage key, not a URL. `MeshRepository` handles both steps and carries its own token provider:

```kotlin
val meshes = MultiSetSDK.meshRepository()

// Map mesh, with the pose relative to the localized map
val map: MapMeshResult? = meshes.loadMapMesh(result.mapCode)

// Object mesh
val meta = meshes.fetchObjectMeshMetadata(objectCode)
val bytes: ByteArray = meshes.downloadMesh(meta.fileUrl!!)
```

`MapMeshResult` carries `mapId`, the GLB `meshBytes`, and `localPosition` / `localRotation` for placing the mesh relative to the localized map.

In the sample app, a localized map mesh appears with a radial reveal animation alongside a gizmo at the result pose, while tracked object meshes are rendered on an invisible anchor node with an animated outline.

If you reuse the sample's renderers (`ar/MeshRenderer`, `ar/ObjectMeshRenderer`), keep these in mind:

- **Materials are compiled for Sceneform's Filament (1.57.1).** The sources are in `app/src/main/assets/materials/*.mat` and the compiled files the app loads are in `app/src/main/res/raw/*.filamat`. After editing a `.mat`, recompile it with `matc` from the Filament 1.57.1 release, using the command in the file's header. A `.filamat` built with any other Filament version will not load.
- **Call the renderers every frame.** From your scene update listener, call `MeshRenderer.onFrame(cameraWorldPosition)`, passing `null` while ARCore isn't tracking. For object tracking, call `ObjectMeshRenderer.onFrame()`. When the scene goes away, call `MeshRenderer.release()`.
- **Draw the camera feed first when showing object outlines.** The outline hides the inside of the object with a depth-only occluder. Sceneform draws its camera feed last by default, so the camera image would be hidden inside the object's outline and that area would show black. Set `arSceneView.cameraStream.renderPriority = Renderable.RENDER_PRIORITY_FIRST`, as `ObjectTrackingActivity` does.

---

## AR Activities

The AR activities live in the **sample app**, not the SDK — they are reference implementations you can copy or replace.

### Localization Activity (`MultiSetLocalizationActivity`)

Handles both single-frame and multi-frame localization modes. The mode is passed at launch time.

#### Single-Frame Mode

- Single-image localization
- Quick position estimation
- Lower latency
- Supports VPS-1 / VPS-2 query mode selection

#### Multi-Frame Mode

- Multi-image localization for higher accuracy
- Captures frames at configurable intervals
- Always runs VPS-1

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
- 3D mesh visualization with radial reveal
- False-positive rejection
- GPS-assisted localization

---

### Object Tracking Activity (`ObjectTrackingActivity`)

Dedicated AR activity for detecting and tracking pre-registered physical objects. Once an object is tracked, its 3D mesh is fetched from the platform and placed in the AR scene with an animated outline.

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
- Animated outline rendered over each tracked object mesh
- Background tracking with configurable retry interval
- Auto-restart on AR tracking loss
- Close confirmation dialog

---

## Permissions

The core SDK's manifest declares only `INTERNET`. Your app declares what its own runtime needs:

| Permission | Purpose | Declared by |
|------------|---------|-------------|
| `INTERNET` | API communication | SDK (merged in) |
| `CAMERA` | AR camera access | Your app |
| `ACCESS_FINE_LOCATION` | GPS for geo hint (optional) | Your app |

The sample app also declares `<uses-feature android:name="android.hardware.camera.ar" android:required="true" />`. Location permission is only required if using GPS-assisted localization.

---

## Building

```bash
# Sample app (ARCore device required to run AR)
./gradlew :app:assembleDebug
./gradlew :app:installDebug

# Sample app unit tests
./gradlew :app:testDebugUnitTest

# Release build
./gradlew :app:assembleRelease
```

The SDK itself ships prebuilt as `app/libs/multiset-sdk.aar` — this repository contains no
SDK sources, so there is nothing to compile for it. To move to a newer SDK build, replace
that file and rebuild.

---

## Further Reading

- **API documentation:** https://developer.multiset.ai/docs
- The sample app under `app/src/main/java/com/multiset/xr/` is the reference integration —
  `ar/ArFrameSource.kt` shows the ARCore→SDK boundary, and `ui/` shows session wiring.

---

## Support

- **Documentation:** https://developer.multiset.ai/docs
- **Developer Portal:** https://developer.multiset.ai
- **Email:** support@multiset.ai

---

## License

Copyright (c) 2026 MultiSet AI. All rights reserved.

Licensed under the MultiSet License. You may not use this file except in compliance with the License. Redistribution in source or binary forms must retain this notice.
