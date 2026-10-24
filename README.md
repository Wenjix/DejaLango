# DejaLango

**You, in any language.** DejaLango is an on-device conversation assistant that shows what someone
said and suggests replies in their language, phrased in your voice. The Android app connects a
Kotlin conversation engine to a phone bench and a browser HUD. Its model adapters target Gemma 4 E2B
for audio and replies, and EmbeddingGemma 2 for retrieval of your past sentences.

Built for the Google Gemma Hardware Hackathon at AGI House, October 24, 2026.

## Status

| Component | Implementation and verification |
|---|---|
| `:engine` | Turn segmentation, prompts, JSON parsing, retrieval, HUD state and traces implemented; **67 JVM tests pass**. |
| `:app` | Mobile and Meta VR fake-model APKs build successfully; **9 JVM tests and 4 on-device tests pass** (prompt rendering; EmbeddingGemma; Gemma on the TPU). Installed and launched on a Pixel; listening/HUD checks pending. |
| LiteRT-LM adapters | Optional mobile APK builds against **LiteRT-LM 0.18.1**, including separate Tensor and Qualcomm runtime variants. Gemma 4 E2B and EmbeddingGemma 2 run on the Pixel 11 Pro XL's TPU; replaying the demo fixture through the app gives replies about 7 s after a turn, against about 10 s on the GPU ([findings](docs/FINDINGS.md)). |
| Fake model | Default implementation for exercising the pipeline without model weights; replies are canned. |
| Browser HUD and simulator | `/hud` serves the HUD; `/sim` places it over a laptop camera or a generated background. Device-to-browser verification pending. |

The accelerated backends, real audio inference and latency targets are implemented paths to verify,
not measured device results. See [architecture](docs/ARCHITECTURE.md) for the contracts and current limits.

## Run the engine and app

The project configures and builds with **JDK 22 and Gradle 9.7.1**, targeting Java/Kotlin bytecode 17.
Use an Android SDK matching `gradle/libs.versions.toml` and an Android device with USB debugging
enabled. Dependency resolution needs network access on the first build.

```bash
./gradlew :engine:test
./gradlew :app:testMobileDebugUnitTest
# With a device connected. Not connectedMobileDebugAndroidTest: it uninstalls the app afterwards, and the
# pushed models with it. EmbedderAndroidTest is skipped until the EmbeddingGemma bundles are pushed.
./gradlew :app:installMobileDebug :app:installMobileDebugAndroidTest
adb shell am instrument -w dev.dejalango.test/androidx.test.runner.AndroidJUnitRunner
# Replay demo_zh.wav through the whole app (about two minutes; -e backend GPU to compare). Keep the phone awake.
adb shell am instrument -w -e fixtureRun true -e class dev.dejalango.app.FixtureRunAndroidTest \
  dev.dejalango.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall dev.dejalango.test
# Offline alternative, using compiler/runtime jars from a local Gradle distribution:
scripts/engine_offline_test.sh /path/to/gradle-distribution

./gradlew :app:installMobileDebug
adb shell am start -n dev.dejalango/dev.dejalango.app.ui.MainActivity
```

Grant microphone and notification permissions. Tap **Start listening** with the fake model enabled.
Speech detection drives the pipeline; the fake model returns sample text rather than transcribing
what you say. The mobile application ID is `dev.dejalango`; the Meta VR flavor uses
`dev.dejalango.metavr`. Each installation has its own data and model directory.

## Open the glasses simulator

With the app listening and the device connected:

```bash
scripts/hud_forward.sh
# Built-in simulator: http://localhost:8765/sim
# Without a camera:  http://localhost:8765/sim?bg=photo
# Raw HUD:           http://localhost:8765/hud
```

Allow the browser to use the laptop camera, or choose `?bg=photo` or `?bg=black`. Arrow keys select
a reply, Enter confirms it, P switches phrasebook/personalized text, C cuts a turn, Escape clears
the HUD, and F toggles fullscreen in the simulator. The raw HUD can also be loaded into hud.xyz.
If multiple devices are connected, set `ANDROID_SERIAL` before running the helper scripts.

Inference stays on the Android device. The HUD server sends conversation state to the connected
browser over USB forwarding. It binds to localhost by default; the optional LAN setting exposes it
to the local network. The simulator camera is a browser background and is not sent to the model.

## Install model weights

First install a debug build of DejaLango: `push_models.sh` streams the weights into its internal
storage through `run-as`. Google Tensor's TPU can't load Gemma from the shared external directory,
where the weights used to go. Authenticate with `hf auth login` and accept the model terms before
downloading weights.

```bash
scripts/fetch_models.sh tensor_g6
# Other supported variants: tensor_g5, sm8750. Omit the argument for generic weights.
scripts/push_models.sh dev.dejalango

# Start with the generic CPU/GPU path:
./gradlew :app:installMobileDebug -PwithLiteRtLm
# Optional Tensor runtime:
# ./gradlew :app:installMobileDebug -PwithLiteRtLm -PwithTensorNpu
# Optional Qualcomm runtime:
# ./gradlew :app:installMobileDebug -PwithLiteRtLm -PwithQualcommNpu
```

For the Meta VR flavor, install and launch that flavor, then run
`scripts/push_models.sh dev.dejalango.metavr`. Models must be pushed into the directory for the
application ID actually installed.

In **knobs**, disable the fake model, tap **Stop**, then **Start listening**. Check the model name
and backend in the glass box: unavailable weights or adapters can select the fake model as a
fallback. `AUTO` tries a device-specific accelerator order and ends with CPU; selecting a backend
explicitly tries that backend, then CPU. The audio encoder defaults to CPU. Vendor runtime versions
are pinned to 2.3.0; vendor-runtime APK builds pass. On a Pixel 11 Pro XL, `AUTO` puts Gemma on
the TPU (`NPU`); Qualcomm initialization remains to be verified.

Verify real inference in this order: generic GPU audio input, CPU fallback, optional Tensor or
Qualcomm backend, then embeddings. Compare the same turns with `TWO_STAGE` and `SINGLE`, and pull
the measurements with `scripts/pull_traces.sh dev.dejalango`. The repository does not include model
weights; [findings](docs/FINDINGS.md) records dated measurements from one Pixel 11 Pro XL.

## Repository map

```text
engine/   Pure Kotlin audio, turns, conversation, voice, prompts, model interfaces, HUD and traces
app/      Android service, platform adapters, bench UI, HUD server and optional LiteRT-LM source root
docs/     ARCHITECTURE.md and DEMO.md
scripts/  Model downloads/pushes, HUD forwarding, trace export and offline engine tests
```

See [the demo script](docs/DEMO.md) for a rehearsal checklist and
[third-party components](THIRD_PARTY.md) for dependency notices.
