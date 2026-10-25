# Getting started with DejaLango

Choose a synthetic emulator preview to explore the interface, or install real models on a phone for conversations. The browser lens connects to a running listening session.

[Emulator preview](#preview-on-an-emulator) · [Real models](#run-real-models-on-a-phone) · [Playbook](#try-the-prepared-playbook) · [Browser lens](#open-the-browser-lens) · [Tests](#run-checks) · [Troubleshooting](#troubleshooting)

## Prerequisites

- A full **JDK 25**, including `jlink`. Set `JAVA_HOME` to that JDK if your editor supplies a stripped runtime. Gradle's daemon criteria select JDK 25 and can download it when absent.
- **Android SDK 36**, its build tools and platform tools (`adb`). Set `ANDROID_HOME`, or set `sdk.dir` in `local.properties` to your SDK's absolute path. Android Studio can configure this for you.
- Network access for the first dependency download. Real inference also needs separately downloaded model weights.

Node.js is needed only for the optional browser renderer check under [Run checks](#run-checks).

Use `./gradlew`; a separate Gradle or Kotlin installation is unnecessary. The wrapper uses Gradle 9.7.1 and targets Java/Kotlin bytecode 17. Android 12 (API 31) is the minimum app OS version.

## Preview on an emulator

Create and start an emulator in Android Studio. List its serial with `adb devices`, then replace `EMULATOR_SERIAL` below.

```sh
./gradlew :app:assembleMobileDebug \
  -PwithLiteRtLm=false -PwithTensorNpu=false -PwithQualcommNpu=false
adb -s EMULATOR_SERIAL install -r app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
adb -s EMULATOR_SERIAL shell am start -n \
  dev.dejalango/dev.dejalango.app.ui.CompanionWalkthroughActivity
```

The labelled walkthrough covers 20 conversation states across four fixtures. Use **Next beat** and **Scene** to explore replies, hints, Talk/Done, corrections and regional language pairs. It does not start audio, run inference or save a profile. It is also available from **Settings → Developer tools** in a debug build.

The normal app starts at `dev.dejalango.app.ui.MainActivity`. With real inference selected, missing model weights produce a setup error. Canned model output requires an explicit developer setting and is labelled synthetic.

## Run real models on a phone

A default build includes LiteRT-LM and the Google Tensor runtime, making the APK **arm64-v8a**. Pixel 11 Pro XL / Tensor G6 and Pixel 10 Pro XL / Tensor G5 have dated device results. The Qualcomm runtime builds; its initialization on hardware remains unverified.

### 1. Select the phone and install a debug build

Enable USB debugging, authorize your computer and choose the phone's serial from `adb devices`. This selection also scopes the helper scripts.

```sh
export ANDROID_SERIAL=PHONE_SERIAL
./gradlew :app:assembleMobileDebug
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
```

Build the real-model APK again even if you previously built the walkthrough with inference flags disabled. Installing the app before pushing weights creates the private directory used by `run-as`.

### 2. Download and push the models

Install the Hugging Face CLI, authenticate with `hf auth login`, and accept the terms for the model repositories used by [fetch_models.sh](../scripts/fetch_models.sh). Model weights are not in this repository.

| Variant | Device path |
|---|---|
| `tensor_g6` | Pixel 11 / Google Tensor G6 |
| `tensor_g5` | Pixel 10 / Google Tensor G5 |
| `sm8750` | Qualcomm Snapdragon 8 Elite; hardware initialization unverified |
| `generic` | Generic CPU/GPU bundles |

```sh
hf auth login
scripts/fetch_models.sh tensor_g6
scripts/push_models.sh dev.dejalango
```

Replace `tensor_g6` with the appropriate variant. The fetcher always includes generic Gemma and EmbeddingGemma bundles; you can name several variants for several phones. Files keep their published names so the app can distinguish generic weights from device-specific bundles.

`push_models.sh` writes into the debug app's **internal storage** through `run-as`. This matters on Tensor G6: Gemma's TPU bundle failed from the earlier external-files FUSE directory. A model already present at full size is skipped.

With `ANDROID_SERIAL` set, only that device is selected. Without it, the script pushes to every connected phone, skips emulators unless explicitly selected, and sends each phone only generic weights and the bundles for its own SoC. Each selected phone must authorize USB debugging.

### 3. Start a conversation

```sh
adb -s "$ANDROID_SERIAL" shell am start -n \
  dev.dejalango/dev.dejalango.app.ui.MainActivity
```

Choose a language pair, tap **Start listening**, and allow microphone access. Tap **I'm talking** immediately before your turn; **Done** is optional because a pause also closes it. Build a profile from your own words in **Your voice** to provide personal context for replies.

The developer bench at **Settings → Developer tools → Bench** shows the actual model and backend. Real inference is the default. `AUTO` tries the device's accelerator order and ends with CPU; an explicit `NPU` or `GOOGLE_TENSOR` falls back through GPU to CPU. The audio encoder defaults to CPU.

<details>
<summary><b>Other runtimes and the Meta VR flavor</b></summary>

Choose the configuration for your device. These are alternative builds.

**Qualcomm:**

```sh
./gradlew :app:assembleMobileDebug -PwithTensorNpu=false -PwithQualcommNpu=true
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
```

**Generic CPU/GPU, without the NPU runtimes' arm64-only restriction:**

```sh
./gradlew :app:assembleMobileDebug -PwithTensorNpu=false -PwithQualcommNpu=false
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
```

**Meta VR:**

```sh
./gradlew :app:assembleMetavrDebug
```

Install the generated Meta VR APK on the intended device before pushing models with `scripts/push_models.sh dev.dejalango.metavr`. Mobile uses `dev.dejalango`; Meta VR uses `dev.dejalango.metavr`. Each installation has separate data and model storage. The flavor provides a Horizon OS manifest overlay; dedicated spatial/glasses renderers are future work.

LiteRT-LM is pinned to 0.18.1 and vendor runtimes to 2.3.0. See [architecture](ARCHITECTURE.md) for backend and model contracts.

</details>

## Try the prepared playbook

This developer preview uses real local models and embeddings. The visual walkthrough does not run the matching pipeline.

The current debug asset supports **you speaking English (US), with the other person speaking Mandarin (Mainland)**. It contains five Beijing scenes: restaurant lunch, café ordering, market bargaining, a DiDi ride and café small talk. The San Francisco and Colombia gallery examples use other pairs and do not use this pack.

1. Install the real-model debug build and both Gemma and EmbeddingGemma weights as above.
2. Choose **English (US) → Mandarin (Mainland)** before starting listening. If you change this pair while the runtime is active, use **Stop** in the developer bench, then **Start listening** to recreate the runtime and reload its playbook.
3. Open **Settings → Developer tools → Bench**. Keep the pipeline shape at **TWO_STAGE**; **SINGLE** skips the playbook.
4. Under **playbook scene**, choose one scene. **Off** is the default. Prefer one scene for a demo: lines repeated across scenes can be ambiguous under **All**.
5. Wait for **playbook: ready** before speaking the partner's line. Indexing runs between turns and pauses for conversation; a line arriving before readiness goes to Gemma.

The scene, threshold and margin are saved. Default semantic matching requires a score of at least 0.85 and a margin of at least 0.03 over other moments. The exact fast path is enabled by default; its `playbookFastPath` setting has no bench switch. The replay test can disable it with `-e fastPath false`.

Prepared cards are authored text. On a hit, the app skips exemplar retrieval and fresh reply generation. The **browser lens labels them Prepared**; the native phone currently uses its ordinary card labels. On an exact fast hit the heard line uses the seed translation and has no pinyin; prepared reply cards retain theirs. Semantic hits keep the model's transcript translation.

Missing playbook assets, a different pair, unavailable semantic embeddings, an index still loading, or no sufficiently clear match leave the normal generation path available. The playbook adds no prepared-card path to the reversed pair or Traditional Mandarin.

See [demo notes](DEMO.md#playbook-demo) for scene presentation and [current architecture](ARCHITECTURE.md#0-current-companion-path) for matching and fallback behavior.

## Open the browser lens

Start listening in the app with real or explicitly synthetic inference. The walkthrough alone does not start the HUD server.

```sh
ANDROID_SERIAL=DEVICE_SERIAL scripts/hud_forward.sh
```

The helper forwards the phone's HUD server, reuses a matching forward, and chooses another laptop port if 8765 is busy. To choose a laptop port yourself, append it: `scripts/hud_forward.sh 9876`.

1. Open **Settings → Developer tools → Bench** on the phone and choose **Copy current HUD link**.
2. Use the laptop port printed by the helper. Replace `/hud` with `/sim` in the copied link, keeping `?token=…` intact.
3. Open it in the laptop browser. The default scene is a bundled synthetic café; **Use my camera** enables a local camera background.

The token changes when the listening runtime is recreated. Pausing and resuming the microphone retains that runtime. State reads, actions and WebSocket upgrades require the current token; actions also bind to the current session. The bridge binds to loopback by default. An optional LAN setting exposes it to the local network.

| Key | Action |
|---|---|
| **T** or **↑** | I'm talking |
| **C** | Done |
| **← / →** | Browse replies |
| **S** | Correct the latest line's speaker |
| **U** | Undo the correction |
| **P** | Pause or resume the display; the microphone keeps listening |

The raw `/hud` is a transparent 600 × 600 green layer. `/sim` puts it over the café or local camera. Camera video stays in the browser and is never sent to the phone or model. Dedicated glasses integration is future work.

## Run checks

Host checks require no device:

```sh
./gradlew :engine:test :app:testMobileDebugUnitTest :app:lintMobileDebug
node scripts/test_hud_renderer.cjs
```

For Android checks, build and install the test APK on an explicitly selected emulator, then run the classes you need:

```sh
./gradlew :app:assembleMobileDebug :app:assembleMobileDebugAndroidTest \
  -PwithLiteRtLm=false -PwithTensorNpu=false -PwithQualcommNpu=false
adb -s EMULATOR_SERIAL install -r app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
adb -s EMULATOR_SERIAL install -r \
  app/build/outputs/apk/androidTest/mobile/debug/app-mobile-debug-androidTest.apk
adb -s EMULATOR_SERIAL shell am instrument -w \
  -e class dev.dejalango.app.CompanionUiAndroidTest \
  dev.dejalango.test/androidx.test.runner.AndroidJUnitRunner
```

Use `am instrument` for a model-loaded phone. `connectedMobileDebugAndroidTest` uninstalls the app afterward and can remove its private model weights. Removing only `dev.dejalango.test` leaves the app's data intact.

`EmbedderAndroidTest` needs EmbeddingGemma weights. `GemmaAcceleratorAndroidTest` needs that phone's accelerator bundles. Its audio case and the opt-in `FixtureRunAndroidTest` also need `demo_zh.wav`, which is not committed; see [fixture setup](../app/src/debug/assets/fixtures/README.md). Replay requires `-e fixtureRun true`. Use `scripts/pull_traces.sh dev.dejalango` to export timings.

## Troubleshooting

| Symptom | Next step |
|---|---|
| Build uses a stripped Java runtime or cannot find `jlink` | Point `JAVA_HOME` to a full JDK 25 and check the Gradle daemon toolchain. |
| App asks for model setup | Install the real-model debug build, fetch the matching weights and push them to the installed app ID. Check the bench's model/backend line. |
| `run-as` cannot find the app | Install the correct debug APK first and confirm `ANDROID_SERIAL` selects that device. |
| Old simulator link is rejected | Copy the current HUD link from the current runtime; retain the token and use the forwarded laptop port. |
| No HUD server during the walkthrough | Open the normal Companion app and start a real or explicitly synthetic listening session. |
| First GPU load is slow after a model push | Re-pushing changes the file timestamp and can rebuild the runtime's GPU cache. See the dated [findings](FINDINGS.md). |

Return to the [project overview](../README.md).
