# Companion Soft press implementation

The native consumer app implements the selected study-07 Soft press direction: Today, Live and Your voice share a fixed adjacent Talk/Done controller. The existing developer bench is behind Settings → Developer tools. The engine routes by the captured role and selected regional language pair, with no consumer modes or Use step.

Work is isolated on `Wenjix/companion-soft-press`, based on `3e00556` from the existing project-1 followups. The working directory is `/Users/wenjiefu/orca/workspaces/DejaLango/companion-soft-press`. The original checkout, installed demo build and physical phone are unchanged. The implementation is packaged on the feature branch for PR review; merging and device installation remain separate steps.

The review build is labelled **October 24, 2026 at 1:06 PM Pacific**: version `0.1.0-companion.20261024.1306-PDT`, with `BuildConfig.BUILD_TIMESTAMP` set to `2026-10-24T13:06:00-07:00`. This is the owner-requested build label. California observes PDT on this date.

## Behavior and boundaries

- Talk arms a wearer turn; Done optionally closes it sooner. Silence also closes the turn. Capture state publishes independently from recognition and generation.
- Their line produces a translation and reply options. Wearer target-language speech is matched once to an offered reply or recorded as original speech, without another reply-writing call. Wearer own-language speech produces translation variants and stays outside shared conversation context.
- A mid-sentence hint has its own pending/ready band, job and stale-result guard. Busy-model hints are dropped. Capture continues while model work is pending.
- A line correction re-recognizes an eligible retained split, shows the actual moved words, and offers Undo and whole-line correction. Missing audio or boundaries produce an explicit whole-line alternative. Undo restores words, roles, offers and affected usage, while preserving input/display state.
- Eight varieties cover English US/UK/Australia, Spanish Colombia/Mexico/Spain, Simplified Mainland Mandarin and Traditional Taiwan Mandarin. Pair changes apply at the next turn while listening. Swap is atomic; same-base pairs are disabled.
- Display pause blanks the lens independently from microphone pause. Navigation and native sheets do not own the listening session. The service keeps audio/model ownership across activity rebinds.
- Usage never creates personal style samples. Legacy `PICKED_REPLY` exemplars are excluded from retrieval. Full owner profiles use per-L1 keys; matching legacy cards are copied idempotently without deleting the old row. Fake models and hashing-only embedders cannot save profiles or exemplars.
- Real model unavailability is an error with a recovery path. Fresh installs do not silently select canned inference. Explicit developer fake sessions carry a visible synthetic label.

The starting timing policy is `CompanionTiming`: 4 s arm, 1.2 s wearer silence, up to 600 ms Done final-word slack, bounded hint hold, and 28 s capture chunks. These are configurable engineering hypotheses from the draft, not measured guarantees or a separate approval of the timings. Language/script selection steers prompts; regional accuracy is not established by fixture rendering.

## Architecture

`CompanionInput` owns the audio ring, turn ownership and deterministic input deadlines on the serialized audio path. Frame timestamps drive controls. Ring gaps are represented as missing samples rather than shifting the time map; microphone reads accumulate partial frames and use a bounded queue.

`CompanionProcessor` freezes config, pair revision and reply offer per turn. It serializes model calls, keeps input updates independent, rejects stale results and separates hints from primary work. `CompanionPrompts`/`CompanionSchemas` omit guessed speaker fields. Only explicit reply-writing request tags receive the personal reply system prompt. Legacy `TurnProcessor`, `Mode` and older contracts remain for compatibility tests; `PipelineRuntime` uses the Companion path.

`ConfigStore` loads persisted settings before the service starts inference, then serializes committed transforms. Failed writes do not publish an optimistic pair that was not saved. Room schema remains version 1; trace CSV columns/order and pinned toolchain are preserved.

The HUD bridge gives each runtime a new capability. `/state`, `/action` and WebSocket upgrades require it; actions also require the current session ID. Tokens are absent from HUD JSON, logs and CSV. Loopback remains the default and wildcard CORS is removed. The optical layer is green and transparent with no opaque backing, glow or text shadow. The simulator uses a local synthetic café or an explicitly requested camera preview.

## Review on an isolated emulator

The debug-only activity reuses the production composables with 20 authored beats across four fixtures. It binds no service, requests no audio and writes no user data. Future practice is a disabled, clearly labelled café concept inside this walkthrough; there is no learning engine, trip inference, streak, scoring or semantic playbook implementation.

```sh
./gradlew :engine:test :app:testMobileDebugUnitTest \
  :app:assembleMobileDebug :app:assembleMobileDebugAndroidTest \
  -PwithLiteRtLm=false -PwithTensorNpu=false -PwithQualcommNpu=false

# Choose an emulator explicitly. These commands must not target the demo phone.
adb -s EMULATOR_SERIAL install -r app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
adb -s EMULATOR_SERIAL shell am start -n \
  dev.dejalango/dev.dejalango.app.ui.CompanionWalkthroughActivity --ei fixture 0 --ei beat 2
```

Fixture indexes: 0 Colombia, 1 Mainland, 2 Taiwan, 3 UK English. Beat indexes: 0–19. Optional debug intent extras: `--es screen today|live|voice`, `--es sheet pair|coach|partner|practice|settings`. In-app entry: Settings → Developer tools → Open synthetic walkthrough. This activity is absent from release manifests.

The Gradle daemon criteria require a full JDK 25 installation with `jlink`. In this workspace, host builds used:

```sh
-Dorg.gradle.java.installations.auto-detect=false \
-Dorg.gradle.java.installations.paths=/Users/wenjiefu/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2/jdk-25.0.3+9/Contents/Home
```

The final build also set `JAVA_HOME` and `org.gradle.java.home` to that full JDK and used `--no-daemon` to avoid reusing the editor's stripped Java runtime. These are local command settings; checked-in toolchain versions are unchanged. The bench's **Copy current HUD link** action copies the current capability link for the forwarding flow.

Do not use `connectedAndroidTest` or generic install commands with the demo phone attached. The verification session uses only `emulator-5580` and explicit instrumentation class lists; accelerator/fixture benchmarks are not part of the UI test run.

## Project-1 design reconciliation

These are implementation deltas to reconcile into the draft, not an assertion that its pending owner calls were individually approved.

| Draft section | Implemented direction |
|---|---|
| §2 experience | Companion consumer shell; keep everyday use complete, with future adventures confined to a labelled concept. |
| §5.2 Talk/Done and timing tables | Adjacent steady actions, optional Done, immediate capture acknowledgment, asynchronous recognition, configurable starting deadlines. Hardware tuning remains pending. |
| §5.3 routing and flip | No operational modes; L1 private variants, L2 match/original, whole/tail correction with transactional Undo and stale guards. Untapped offered-reply matching works in either Catch setting. |
| §5.7 controls | Consumer Thumb dock replaces bench-first controls; reply browsing lives on the focused card. Green HUD uses T/ArrowUp Talk, C Done, left/right browse, S latest-line correction, U Undo, P display pause. Wire `cut` means guarded Done; operator Cut stays in the bench. |
| §8 pending calls 15–21 | Soft press and fixed Talk/Done placement are selected. Do not turn that visual selection into approval of timing/latency claims. Default partner cue waits for recognized target-language speech. Guided early cue remains an external known-script presentation concept. |
| §10 risks | Emulator/source evidence covers state and UI behavior; microphone recognition, overlaps, optical readability, real haptics and latency need field/device validation. |
| §11.2 later work | Hold/ring UI, volume-key mapping, semantic learning, context layers and automatic adventures remain future work. Core actions retain discrete-press mapping. |

## Verification evidence

The local evidence directory is `.impeccable/companion-native/`; the independent review packet is `.impeccable/review/companion-native/`. Captures are actual API36 AOSP arm64 emulator framebuffers at 320, 390, 440, 800 and 1280dp, including dark theme, font scales 1.3/2.0 and Remove animations. Native code skips the web design detector. Android Compose Accessibility Test Framework checks accompany interaction tests; [Android's testing guidance](https://developer.android.com/develop/ui/compose/accessibility/testing) describes that scope. Font axes are explicit because the supplied variable TTFs default to thin weights; this follows [Android's variable-font API](https://developer.android.com/develop/ui/compose/text/fonts).

Final counts, review disposition and any remaining limits are recorded in `verification.md` beside this document. No new physical-device inference, latency, reach, optics or haptic measurements are claimed. Existing Pixel measurements in `docs/FINDINGS.md` concern the earlier installed pipeline.
