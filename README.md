# DejaLango

**You, in any language.** DejaLango is an on-device conversation assistant that shows what someone
said and suggests replies in their language, phrased in your voice. The Android app connects a
Kotlin conversation engine to the native Companion app and a synchronized browser HUD. Its model
adapters target Gemma 4 E2B for audio and replies, and EmbeddingGemma 2 for retrieval of your past
sentences.

Built for the Google Gemma Hardware Hackathon at AGI House, October 24, 2026.

## Status

The Companion app ("Soft press") has three screens, Today, Live and Your voice, with a Talk/Done
control that stays in place on all of them. It supports regional language pairs, word hints,
automatic matching of a reply you read aloud, and reversible speaker correction. There are no modes
to pick and no manual Use step. The developer bench is behind Settings → Developer tools.

A debug walkthrough plays 20 beats across Colombia, Mainland Mandarin, Taiwan Mandarin and UK English
fixtures. It uses synthetic content and never starts audio or saves a profile. Future practice is a
labelled static concept, not a learning engine.

| Check | Result |
|---|---|
| JVM tests | **127 engine and 31 app tests pass.** |
| On-device tests | 14 tests in 8 classes: Companion UI, startup, HUD server, voice ownership, prompt rendering, EmbeddingGemma, Gemma on the TPU and an opt-in fixture replay. The last device runs on record are from October 23, before the Companion app ([findings](docs/FINDINGS.md)). |
| Inference | A default build compiles the LiteRT-LM adapter against **LiteRT-LM 0.18.1** and bundles the Google Tensor NPU runtime. On October 23, on a Pixel 11 Pro XL, Gemma 4 E2B and EmbeddingGemma 2 ran on the TPU, and the earlier pipeline gave replies a median 7.1 s after a turn (10.1 s on the GPU, one replay each). These figures don't cover the Companion app. |
| Companion on a phone | Known issue: on October 24, this build replayed the demo fixture on a Pixel 10 Pro XL and wrote no replies. The transcription prompt shows the language field as `en\|zh`, the model copied that value, and the language-pair check rejected every line. |
| Phone-to-browser HUD | On October 24, a laptop browser opened `/sim` from the phone through `scripts/hud_forward.sh`, on the build before the Companion app. The Companion build's tokenized `/sim` hasn't been checked from a phone. |

Missing weights produce an error that asks for model setup. Canned inference needs an explicit
developer setting and carries a synthetic label.

## Build and preview

Gradle 9.7.1 runs on a full **JDK 25** (with `jlink`): the wrapper picks one through
`gradle/gradle-daemon-jvm.properties` and downloads it if none is installed. The project targets
Java/Kotlin bytecode 17. Use an Android SDK matching `gradle/libs.versions.toml`, and set
`ANDROID_HOME` or `sdk.dir` in `local.properties` to its path (Android Studio writes the latter). The
first build needs network access.

A default build includes LiteRT-LM and the Google Tensor runtime, so its APK is arm64-v8a only. The
preview build below leaves them out, since the walkthrough runs no inference.

```sh
./gradlew :engine:test :app:testMobileDebugUnitTest
./gradlew :app:assembleMobileDebug :app:assembleMobileDebugAndroidTest \
  -PwithLiteRtLm=false -PwithTensorNpu=false -PwithQualcommNpu=false

# Select an emulator explicitly; leave the demo phone alone.
adb -s EMULATOR_SERIAL install -r app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
adb -s EMULATOR_SERIAL shell am start -n \
  dev.dejalango/dev.dejalango.app.ui.CompanionWalkthroughActivity
```

The walkthrough is also available from Settings → Developer tools. The real app opens at
`dev.dejalango.app.ui.MainActivity` with an empty state until you tap **Start listening**; allow the
microphone when asked. The mobile application ID is `dev.dejalango`; the Meta VR flavor uses
`dev.dejalango.metavr`, with separate data and model storage.

## Glasses simulator

While the app is listening, with real or explicitly synthetic inference, forward the HUD port:

```sh
ANDROID_SERIAL=DEVICE_SERIAL scripts/hud_forward.sh
# It picks another laptop port if 8765 is taken, or pass one: scripts/hud_forward.sh 9876
```

Copy the current tokenized HUD link from Developer tools → Bench. Keep its token when replacing
`/hud` with `/sim`, and use the laptop port the script prints. The simulator starts with a bundled
synthetic café; camera preview is an explicit button. T/ArrowUp invokes Talk, C Done, left/right
browses replies, S corrects the latest line, U undoes, and P pauses the display. `cut` on the wire
means guarded Done; operator Cut remains a separate bench action.

The bridge binds to loopback by default. State, actions and WebSocket upgrades require a capability
that rotates when the runtime restarts; actions also carry its session ID. The optical layer is green
and transparent. Simulator video is a local browser background and is never sent to the model.

## Install model weights

The repository does not include the Gemma or EmbeddingGemma weights. Install a debug build first:
`push_models.sh` streams the weights into its internal storage through `run-as`. On a Tensor G6, the
TPU couldn't load Gemma from the app's external files directory (a FUSE mount), where the weights
used to go. Authenticate with `hf auth login` and accept the model terms before downloading. Set
`ANDROID_SERIAL` so installs and pushes reach the phone you mean.

```bash
scripts/fetch_models.sh tensor_g6
# Other supported variants: tensor_g5, sm8750. Omit the argument for generic weights.
./gradlew :app:installMobileDebug
scripts/push_models.sh dev.dejalango

# Add the Qualcomm runtime, or leave out Tensor for other ABIs:
# ./gradlew :app:installMobileDebug -PwithQualcommNpu
# ./gradlew :app:installMobileDebug -PwithTensorNpu=false
```

For the Meta VR flavor, install it with `./gradlew :app:installMetavrDebug`, then run
`scripts/push_models.sh dev.dejalango.metavr`. Models must be pushed into the directory for the
application ID actually installed.

Real inference is the default. Start listening, then check the model name and backend in the glass
box at Settings → Developer tools → Bench. `AUTO` tries a device-specific accelerator order and ends
with CPU. An explicit `NPU` or `GOOGLE_TENSOR` falls back to GPU, then CPU; `GPU` falls back to CPU.
The audio encoder defaults to CPU. Vendor runtimes are pinned to 2.3.0. On a Pixel 11 Pro XL, `AUTO`
puts Gemma on the TPU (`NPU`). The Qualcomm runtime builds but hasn't been initialized on hardware.
Pull traces with `scripts/pull_traces.sh dev.dejalango`.

## Test on a device

Install the test APK and run classes with `am instrument`, not `connectedMobileDebugAndroidTest`: it
uninstalls the app afterwards, and the pushed weights with it. Run the Companion UI tests on an
emulator.

EmbedderAndroidTest is skipped until EmbeddingGemma is pushed, and GemmaAcceleratorAndroidTest until
this phone's accelerator bundles are (`tensor_g6` on a Pixel 11). Its audio test and
`FixtureRunAndroidTest` also need the demo fixture, which isn't committed: add
`app/src/debug/assets/fixtures/demo_zh.wav` before you build (see the README in that folder). The
replay runs only with `-e fixtureRun true`.

```bash
export ANDROID_SERIAL=DEVICE_SERIAL
./gradlew :app:installMobileDebug :app:installMobileDebugAndroidTest
adb shell am instrument -w -e class dev.dejalango.app.GemmaAcceleratorAndroidTest \
  dev.dejalango.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall dev.dejalango.test
```

## Repository map

```text
engine/   Pure Kotlin audio, turns, conversation, voice, prompts, model interfaces, HUD and traces
app/      Native Companion UI, listening service, platform adapters, debug bench, secured HUD bridge
docs/     ARCHITECTURE.md, DEMO.md and FINDINGS.md
scripts/  Model downloads/pushes, HUD forwarding, trace export and a HUD renderer check
```

See [the demo script](docs/DEMO.md) for a rehearsal checklist and
[third-party components](THIRD_PARTY.md) for dependency notices.
