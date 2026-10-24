# DejaLango

**You, in any language.** DejaLango is an on-device conversation assistant that shows what someone
said and suggests replies in their language, phrased in your voice. The Android app connects a
Kotlin conversation engine to the native Companion app and a synchronized browser HUD. Its model adapters target Gemma 4 E2B
for audio and replies, and EmbeddingGemma 2 for retrieval of your past sentences.

Built for the Google Gemma Hardware Hackathon at AGI House, October 24, 2026.

## Status

The Companion Soft press implementation provides Today, Live and Your voice, a persistent Talk/Done
controller, regional language pairs, word hints, automatic spoken-reply matching and reversible speaker
correction. The developer bench remains behind Settings → Developer tools. There are no consumer modes
or manual Use step. The [implementation record](docs/implementation/companion-soft-press.md) explains
architecture, scope and draft timing assumptions; [verification](docs/implementation/verification.md)
records the current checks and limits.

The engine and Android adapter have host tests. The native UI has an explicit debug walkthrough with
20 beats across Colombia, Mainland Mandarin, Taiwan Mandarin and UK English fixtures, plus emulator
interaction/accessibility checks. It uses synthetic content and never starts audio or saves a profile.
Future practice is a labelled static concept, not a learning engine.

Real LiteRT-LM inference remains local. Earlier Pixel measurements are in [FINDINGS.md](docs/FINDINGS.md);
they do not establish the new Talk/Done candidate's performance. The physical demo phone and installed
build remain unchanged during this feature-branch implementation. Missing real weights produce an
honest error; canned inference requires an explicit developer setting and carries a synthetic label.

## Build and preview

Use a full **JDK 25** (including `jlink`), the pinned Android SDK and Gradle wrapper. The project targets
Java/Kotlin bytecode 17. Dependency resolution may require network access on the first build.

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
`dev.dejalango.app.ui.MainActivity`, with a complete empty state until you choose to listen. Grant
microphone access for real conversations. The mobile application ID is `dev.dejalango`; the Meta VR
flavor uses `dev.dejalango.metavr`, with separate data/model storage. Do not run `connectedAndroidTest`
on the demo phone: it can uninstall app data and weights. Use explicit instrumentation class lists on
a selected emulator for UI validation.

## Glasses simulator

While a real or explicitly synthetic runtime is listening, use:

```sh
ANDROID_SERIAL=DEVICE_SERIAL scripts/hud_forward.sh
```

Copy the current tokenized HUD link from Developer tools → Bench. Keep its token when replacing
`/hud` with `/sim`. The simulator starts with a bundled synthetic café; camera preview is an explicit
button. T/ArrowUp invokes Talk, C Done, left/right browses replies, S corrects the latest line,
U undoes, and P pauses the display. `cut` on the wire means guarded Done; operator Cut remains a
separate bench action.

The bridge binds to loopback by default. State, actions and WebSocket upgrades require a capability
that rotates when the runtime restarts; actions also carry its session ID. The optical layer is green
and transparent. Simulator video is a local browser background and is never sent to the model.

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

In Settings → Developer tools → Bench → **knobs**, select real inference and restart listening.
Check the model name and backend in the glass box. Unavailable weights or adapters produce an error;
they never silently select the fake model. `AUTO` tries a device-specific accelerator order and ends with CPU; selecting a backend
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
app/      Native Companion UI, listening service, platform adapters, debug bench, secured HUD bridge
docs/     ARCHITECTURE.md and DEMO.md
scripts/  Model downloads/pushes, HUD forwarding, trace export and offline engine tests
```

See [the demo script](docs/DEMO.md) for a rehearsal checklist and
[third-party components](THIRD_PARTY.md) for dependency notices.
