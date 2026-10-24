# DejaLango architecture

DejaLango turns short audio turns into a transcript and suggested replies in the other person's
language, phrased in the wearer's voice. A pure Kotlin engine produces `HudState`; the Android app
supplies audio, inference, storage and displays.

The engine has **67 passing JVM tests** and the app has **9 passing JVM tests**. Mobile and
Meta VR fake-model APKs build successfully, as does the optional mobile build against LiteRT-LM
0.18.1. Real-device inference, vendor runtime initialization and performance need verification.
Examples below describe the current design; source types are the authority for exact signatures.
Section 11 records the main decisions and open checks.

## 0. Product modes

| Mode | Trigger and audio window | HUD output |
|---|---|---|
| Reply options | End of the other person's turn; up to 28 seconds | Transcript, translation and replies in L2 |
| Say it my way | End of the wearer's L1 turn | The same meaning in L2, using the wearer's style |
| Word-finding | Mid-sentence pause; trailing 8 seconds | A word suggestion and alternatives when confidence is sufficient |

L1 is the wearer's language and L2 is the other person's language. Defaults are English and
Mandarin Chinese. Replies carry phrasebook and personalized versions, romanization and a
back-translation. The bench can show the voice card and retrieved sentences behind the prompt.

## 1. Principles

- Keep the engine independent of Android. Device-specific behavior implements engine interfaces.
- Publish one observable `HudState` for the phone and browser renderers.
- Record turn timing and failure flags in `TurnTrace`; use measurements to compare pipeline shapes.
- Preserve useful visible state when a turn fails, and expose errors on the bench.
- Keep models replaceable. The fake speech model and hashing embedder exercise the pipeline
  without downloaded weights.
- Run inference locally. Quick-card audio stays in memory; persistent exemplars and owner cards
  use the app's storage. The local HUD server shares conversation state with a connected browser.

## 2. System overview

```text
Microphone / WAV fixture
        |
AudioSource -> RingBuffer -> VAD -> TurnSegmenter -> TurnEvent
                                                     |
ConversationState <------------------------------ TurnProcessor
                                                     |
VoiceCard + ExemplarStore -> Retriever + Embedder ----+
                                                     |
                                      HudState + TurnTrace
                                         |            |
                                phone / browser     trace CSV
```

The phone hosts the engine for the browser HUD. The simulator adds a camera or generated
background around the raw HUD; it does not run inference. The `metavr` flavor supplies a Horizon
OS manifest overlay. Dedicated glasses and spatial renderers are not implemented.

## 3. Modules

| Module | Responsibility |
|---|---|
| `:engine` | Kotlin/JVM audio contracts, turn segmentation, conversation, retrieval, prompts, parsing, HUD state and traces |
| `:app` | Android foreground service, adapters, Room and DataStore, Compose bench, HTTP/WebSocket server and browser assets |

The dependency is `:app` to `:engine`. App packages group platform adapters, service wiring, UI
and HUD code. LiteRT-LM implementations live in `app/src/litertlm/kotlin` and enter the build only
with `-PwithLiteRtLm`. `AppGraph` loads them by class name. The fake model is enabled by default.

## 4. Engine

Package root: `dev.dejalango.engine`.

### 4.1 Audio and turns

`AudioSource` publishes timestamped `AudioFrame` values. Each frame contains 512 samples at
16 kHz, or 32 ms of mono audio. `RingBuffer` retains up to 30 seconds. The VAD supplies a speech
probability and the segmenter emits levels, speech start, turn end and resumed speech events.

`TurnSegmenter` uses hysteresis and explicit silence/speech/pending-end states. Its policy controls
minimum speech, end silence, grace for resumed speech, pre-roll, maximum turn length and cooldown.
One pause emits one turn. Resumed speech can cancel stage A; word suggestions also pass a
confidence gate in the processor.

| Policy default | Reply options / Say it my way | Word-finding |
|---|---|---|
| Speech on/off threshold | 0.5 / 0.35 | 0.5 / 0.35 |
| Minimum speech | 600 ms | 1,500 ms |
| End silence | 700 ms | 500 ms |
| Resume grace | 300 ms | 0 ms |
| Pre-roll | 300 ms | 0 ms |
| Maximum turn | 28 seconds | 28 seconds, with an 8-second trailing window |
| Cooldown | 0 ms | 2,000 ms |

The default resume confirmation is 250 ms and the retained tail is 100 ms. A manual **Cut** action
can end the current turn. Policy parameters live in code; the bench does not expose threshold or
noise-calibration controls.

### 4.2 Conversation state

`ConversationState` retains a bounded turn history, the previous suggestions and a learned count.
The model returns `speaker` and `lang`; there is no separate audio diarization system. Reply mode
responds to `THEM`, while the two-stage say-it-my-way path responds to `ME`. Stage A labels the
speaker by language, so the wearer reading an L2 card aloud comes back as `THEM`. Both modes
therefore match the transcript against the cards on offer, replies or say-it-my-way variants,
before they use the label.

Confirming a selected reply or matching the wearer's transcript to a recent suggestion emits a
used-reply event. The app embeds and stores that reply with source `PICKED_REPLY`. Confirmation
records user intent; it is not proof that the user spoke the reply.

Each suggestion set (personalized and phrasebook lines) can be used once. Matching or confirming a
reply consumes the set, and so do **Clear** and a newly accepted line. The cards may stay on the HUD,
but a consumed set never matches again, so in reply mode the other person's next line always
reaches stage B. After a confirmation, the very next utterance is checked once against the
confirmed reply. The wearer usually says that reply next; it is already recorded, so it is absorbed
instead of being taken as a new line.

Matching uses a normalized edit distance of at most 0.35, or 0.2 for replies shorter than ten
characters. Mandarin answers repeat the question's frame, so the other person's next line often
falls within 0.35 of a short card. Reading the start of a reply counts when that start has at least
four characters and covers at least half of the reply. A line that contains a card does not count.

### 4.3 Voice cards and retrieval

`VoiceCard` describes register, directness, warmth, humor, sentence length and recurring phrases.
`Exemplar` pairs a sentence and language with its source and embedding. The retriever selects
same-language examples with cosine similarity and maximal marginal relevance for diversity.
Vectors are normalized and truncated to 256 dimensions. The query, what was just said, and the stored
sentences get EmbeddingGemma's two task prompts, `task: search result | query: …` and `title: none | text: …`.
Vectors from another embedder, such as an earlier build or the hashing fallback, don't compare with these:
after changing the embedder, wipe and then seed again, since seeding skips sentences already indexed.

Owner-card building accepts transcript sentences, persists the card and stores embedded L1
examples, skipping sentences that are already indexed. The bench's **seed voice** action feeds it the
bundled persona corpus `assets/voice_seed_<l1>.txt`. The quick-card flow transcribes the last 60
seconds held by the rolling buffer and builds an in-memory card. It also indexes the transcribed L1
sentences as exemplars, keeping only sentences in the L1 script so the other person's lines are not
stored as the wearer's. The bench shows how many exemplars are indexed.

### 4.4 Model contract

`SpeechLanguageModel` exposes `info`, `warm(systemPrompt)`, `generate(request)`, `prepare()` and
`close()`. Requests contain optional audio, prompt text, token budget, thinking flag, a request tag
and an optional JSON Schema. Generation returns a cancellable flow of text chunks and decode stats.
The caller serializes model work on a dedicated dispatcher.

The LiteRT-LM adapter:

- Initializes an engine from a model file and selected backend. `AUTO` tries Tensor, NPU, GPU and
  CPU where appropriate for the device; Qualcomm starts with NPU, and generic devices use GPU/CPU.
  Explicit backend selection falls back to CPU. The winner appears in `ModelInfo.backend`.
- Loads bundles from the app's internal storage first (5.3). On the FUSE-backed external files
  directory, Google Tensor's TPU runtime can't read Gemma's `_Google_Tensor_Gx` bundle (`preadv2`
  fails with EOPNOTSUPP), and `AUTO` then lands on the GPU.
- Selects generic or SoC-specific bundles using `Build.SOC_MODEL`. Vendor NPU runtime libraries are
  optional build dependencies selected with `-PwithTensorNpu` or `-PwithQualcommNpu`.
- Gives a conversation the response format only when its request carries a schema (structured
  output). The API has no session cloning, so every request creates a conversation, and with the
  response format that takes about 2.5 seconds on the Pixel 11 Pro XL: LiteRT-LM builds an
  llguidance tokenizer over the whole vocabulary for each one. Without it, about 10 ms; the
  engine's first conversation, which also builds the audio encoder, is made while loading. With
  structured output on, `prepare()` creates one spare ahead of time: a plain one for Stage A (also
  transcription and the voice card), or one with the system prompt for single and word. Stage B
  creates its own. LiteRT-LM drops its record of the last session only when
  no session is left, so a spare that outlives Stage A's conversation makes Stage B deep-copy Stage
  A's KV cache before its prefill (measured at about 2.1 seconds, nearly all that a Stage B spare
  saves). For the same reason a request closes a spare of the wrong kind before it runs. `warm()`
  drops a system spare when the prompt changes. Prefill still runs with the request.
- Supplies mono 16-bit WAV bytes through `Content.AudioBytes` before the text prompt. The audio
  encoder defaults to CPU, with GPU and same-as-main options.
- Streams messages with `sendMessageAsync`, maps cancellation to `cancelProcess()`, and optionally
  supplies a schema through `ResponseFormat.json`.

The optional mobile APK builds against the pinned LiteRT-LM 0.18.1 artifact. Audio encoder support
and vendor acceleration still need device verification.

### 4.5 Turn processing

`TurnProcessor` consumes events and owns observable HUD state, traces and used-reply events.
Only one generation uses the model at a time. A new completed turn cancels the previous generation
and waits until that generation has left the model, because native prefill finishes even after
cancellation. Each generation of a voice-card build takes the same lock, so a turn that ends during a
build runs between two of its generations.

Between turns, and after `warm()`, the processor calls `prepare()` to create the next turn's
conversation off the critical path. It passes the request tags the next turn will make, in order, and
whether they carry a schema; `LiteRtLmModel` prepares for the first one only, and only with structured
output (4.4). A mode or settings change re-arms it. It does one unit per lock hold and stops once a newer turn has
started. A turn that ends meanwhile therefore waits for at most the one creation in progress. A turn
that is cancelled (a resume, Confirm or Clear) still schedules a refill. If speech resumes while the
newest turn is still waiting for the model, that turn is cancelled too, before it uses a spare. That avoids the earlier pool's
stall, which created and prefilled two conversations under the turn's lock. For each request,
`LiteRtLmModel` logs whether the conversation was a spare or how long creating it took, plus the time
to first token and the decode speed.

`TWO_STAGE` is the default:

1. Stage A transcribes and translates audio into `{speaker, lang, heard, heard_l1}`. Its prompt
   carries no earlier turns or suggestions, which a 2B model would copy into the transcript. The
   transcript is displayed as soon as the stage completes.
2. Stage B retrieves examples using `heard_l1` and generates replies containing `plain`, `yours`,
   `roman` and `back` fields. Its prompt gives the current line once and earlier turns as context.
   It leaves out the voice card, context and exemplar sections when they are empty.

`SINGLE` performs transcription and replies together, using examples retrieved from the previous
turn's text. This avoids a second model call but uses older retrieval context. Word-finding has its
own prompt and defaults to a confidence threshold of 0.45.

The parser accepts code fences and surrounding text, extracts JSON, repairs truncated output when
possible, and falls back to visible raw text when no structured replies can be parsed. Traces record
repair, parse failure, timeout and cancellation. Output is parsed after collection; field-by-field
streaming updates are not implemented.

### 4.6 HUD state

`HudState` includes phase, mode, audio level, heard text, replies, selection, phrasebook toggle,
word suggestions, learned count, voice card, retrieved examples, model details, error and trace.
Renderers decide which fields to show. The raw HUD shows short text, replies, romanization and
selection; the bench also shows model/backend details, examples and timing summaries.

### 4.7 Prompts and schema

`Prompts.kt` contains system, stage, translation, word-finding and voice-card templates. Files named
`assets/prompts/<template>.txt` override the corresponding Kotlin templates at startup. Prompt
placeholders must remain intact. No override text files are required for the defaults to work.

Replies use this shape:

```json
{"replies":[{"plain":"Could you tell me where the station is?","yours":"Hey, is the station around here?","roman":null,"back":"Hey, is the station around here?"}]}
```

The configured L2 determines output language and romanization. Defaults request two replies,
up to 12 words or 20 CJK characters each. Constrained JSON decoding is disabled by default.

### 4.8 Configuration

`PipelineConfig` defaults to reply mode, English/Mandarin, `TWO_STAGE`, backend `AUTO`, CPU audio
encoding, two replies, six examples, six context turns and an 8-second generation timeout. Thinking,
TTS and constrained JSON are off. DataStore persists the configuration.

The bench exposes mode, pipeline shape, main/audio backend, reply count, exemplar count, thinking,
structured output and TTS controls. Fake-model and replay settings are separate from pipeline
configuration. Restart listening after changing model or backend selection. Language types support
additional pairs, but the current bench does not expose language pickers.

### 4.9 Tracing

Each processed turn emits `TurnTrace` with timestamps, audio duration, pipeline shape, backend,
token counts, speed and failure flags. The app appends CSVs under its external-files `traces/`
directory. `scripts/pull_traces.sh dev.dejalango` copies those files into the local ignored `traces/`
directory. The bench displays a summary rather than a full trace visualization.

## 5. Android app

### 5.1 Process layout

`ListenService` is a microphone foreground service that owns `PipelineRuntime`. The runtime owns
audio, VAD, segmentation, the model, tracing and the HUD server. `MainActivity` hosts the Compose
bench and binds to the service. Start listening from the foreground after granting permissions.
Stopping the service releases model, audio and server resources.

### 5.2 Dependency injection

`DejaLangoApp` creates `AppGraph`, which supplies lazy stores and adapter factories. Fake and real
models use the same engine interfaces. Missing weights or an unavailable optional adapter can
select the fake speech model; embedding failure can select the hashing embedder. Inspect the model
line and logcat to confirm the real path is active; the bench's `embedder=` field names the bundle and backend.

### 5.3 Platform adapters

| Adapter | Behavior |
|---|---|
| `MicAudioSource` | `AudioRecord` with `VOICE_RECOGNITION`, mono 16 kHz PCM, 512-sample reads, a buffer at least four times Android's minimum and sample-based timestamps |
| `SileroVad` | ONNX Runtime with recurrent state and context; the bundled v5 graph uses 64 context samples, 512 frame samples and state `[2,1,128]` |
| `LiteRtLmModel` | Optional speech/reply adapter |
| `LiteRtLmEmbedder` | Optional EmbeddingGemma 2 text adapter: this phone's accelerator build first (`_Google_Tensor_G6` through the `NPU` backend on a Pixel 11), then the generic build on CPU, then the hashing embedder; each attempt is logged |
| `RoomExemplarStore` | Persistent exemplars and owner voice cards in `dejalango.db` |
| `ConfigStore` | Configuration and app settings in the `dejalango` preferences DataStore |
| `AndroidVoiceOutput` | Optional Android TTS; tolerates an absent TTS engine |
| `ModelFiles` | Resolves `.litertlm` weights in the app's internal `files/models/` directory, then in the external-files `models/` directory that earlier pushes used |

Silero VAD is bundled as an asset. Large language-model weights are downloaded separately and
streamed into internal storage with `scripts/push_models.sh dev.dejalango`, through `run-as`, so
the installed application must be a debug build.
The Meta VR application ID is `dev.dejalango.metavr` and needs its own model push.

### 5.4 Renderers and local protocol

| Route | Purpose |
|---|---|
| `/hud` | Raw HUD page, also available at `/`, `/hud.html` and `/index.html` |
| `/sim` | Built-in glasses simulator, also at `/sim.html`; camera, generated-photo or black background |
| `/state` | Current state as JSON |
| `/action?a=<json>` | One action sent through an HTTP request |
| `/ws` | State updates out and action messages in over WebSocket |

Run `scripts/hud_forward.sh`, then open `http://localhost:8765/sim`. The simulator uses a local
camera background by default; `?bg=photo` and `?bg=black` work without a camera. Keys are arrows
for selection, Enter for confirmation, P for phrasebook text, C for cut, Escape for clear and F
for simulator fullscreen. hud.xyz can load the raw `/hud` URL as an alternative renderer.

`HudJson` sends a JSON object on each state update. Incoming actions include `next`, `prev`,
`confirm`, `select`, `togglePlain`, `clear`, `cut` and `mode`, for example
`{"action":"select","index":0}`. The built-in simulator renders the raw HUD in an iframe with
an additive blend over its background. Its camera stream stays in the browser.

### 5.5 Flavors, permissions and storage

The `mobile` flavor uses application ID `dev.dejalango`; `metavr` adds `.metavr` and a Horizon
manifest overlay. Both share the bench and engine.

The manifest requests microphone, foreground-service and notification permissions, plus
`INTERNET` for the local HTTP/WebSocket server. The server binds to `127.0.0.1` by default. The
optional LAN setting changes that binding. Model inference uses downloaded local weights; the app
does not require a cloud inference service. HUD state, including text and card details, is sent to
connected renderers.

Room stores exemplars and owner cards. Quick cards and raw captured audio stay in memory. **Wipe**
clears exemplars, stored cards and conversation state; it does not remove model weights or trace
CSVs. New application IDs have separate storage and require a fresh model push.

## 6. Latency targets

| Stage | Target |
|---|---|
| End-of-turn silence | 700 ms by policy |
| Stage A encode and prefill | At most 500 ms |
| Stage A decoding | At most 800 ms |
| Retrieval | At most 100 ms |
| Stage B prefill and decoding | At most 1.5 seconds |
| Turn end to transcript | About 1.3 seconds |
| Turn end to replies | About 3 seconds |

These are budgets to test, not benchmark results; dated measurements are in [FINDINGS](FINDINGS.md). Compare the same inputs across backends and
pipeline shapes. Fewer replies, fewer examples and shorter context can reduce work. Device thermal
behavior needs measurement; automatic thermal adaptation is not implemented.

## 7. Failure behavior

| Failure | Current behavior or recovery |
|---|---|
| Missing real model or adapter | `AppGraph` can choose fake speech or hashing embeddings; inspect the model and logs |
| Backend initialization fails | Try the remaining configured backend order; an explicit backend falls back to CPU |
| Model warm fails | HUD enters error state with the failure text |
| Generation times out | Cancel that generation and show an error while retaining available context |
| Output cannot be parsed | Flag `parseFailed`; use raw text when available |
| VAD remains in speech | Maximum turn length ends the turn; the user can also tap **Cut** |
| Silero cannot load | Log the failure and use energy VAD |
| Replay fixture missing | Log the failure and use the microphone |
| HUD server cannot start | Log the failure; the runtime exposes no HUD URL |

## 8. Verification

The existing engine suite covers turn-policy transitions and timing, ring-buffer behavior, prompt
building, malformed/truncated JSON, retrieval and pipeline behavior. Run `./gradlew :engine:test`
or the offline compiler helper. The verified suite has 67 engine tests, 9 Android JVM tests and 4 Android instrumentation
tests. One instrumentation test covers prompt rendering and unresolved-variable rejection using
Android's regex engine. One loads EmbeddingGemma through `AppGraph` and checks that this phone's
accelerator build wins and that its vectors match meaning across English and Mandarin. Two load Gemma
with `AUTO`: one checks that it lands on this phone's accelerator bundle and runs a Stage A turn from
the demo fixture, the other that the embedder keeps its accelerator build beside Gemma and returns
the same vector after Gemma has run. `FixtureRunAndroidTest` replays the demo fixture through
`PipelineRuntime` and logs each turn's timings; it runs only with `-e fixtureRun true`. Run them with
`am instrument` as the README shows, not `connectedMobileDebugAndroidTest`, which uninstalls the app
afterwards and deletes the pushed models.

The app includes JVM adapter tests and a WAV replay source. Debug fixture locations are documented,
but recorded WAVs are not supplied by default. A fixture-based demo requires adding the intended
audio first. Gradle 9.7.1 configuration, both fake-model flavor builds and the optional LiteRT-LM
mobile build pass with JDK 22. Installation and activity launch succeeded on a Pixel 11 Pro XL.
Listening and live HUD checks could not run while the device was locked. Remaining checks are:

- Separate Tensor and Qualcomm runtime APK builds pass. The Tensor runtime initializes on a Pixel 11
  Pro XL; initialize the Qualcomm one on hardware.
- Launch, grant permissions, start/stop listening and exercise fake-model actions on the phone/HUD.
- Real audio inference runs on the GPU and the TPU, and embeddings on the TPU and CPU (instrumentation
  tests). The demo fixture replayed through the app gives replies about 7 s after a turn on the TPU,
  against the 3-second budget in section 6.
- Measure latency, cancellation and repeated turns; export traces and rehearse the demo.

## 9. Toolchain and dependencies

The repository declares AGP 9.3.2 with built-in Kotlin, Kotlin 2.3.0, KSP 2.3.4, Gradle 9.7.1,
Compose BOM 2025.09.01, Room 2.8.3, coroutines 1.10.1, compile/target SDK 36, minimum SDK 31 and
JVM target 17. Gradle configuration, the default flavor builds and the optional LiteRT-LM mobile
build have passed using JDK 22.

LiteRT-LM is pinned to `0.18.1`, which resolves and compiles with the app's optional adapters.
Vendor NPU runtimes are pinned to `2.3.0`; separate Tensor and Qualcomm APKs build successfully.
Runtime initialization and compatibility still require hardware validation. ONNX Runtime resolves for the default app builds.
Core dependencies are coroutines and serialization in the engine, and AndroidX/Compose, Room,
DataStore, ONNX Runtime and NanoHTTPD in the app. See `THIRD_PARTY.md` for component notices.

## 10. Repository layout

```text
DejaLango/
  README.md
  THIRD_PARTY.md
  docs/{ARCHITECTURE,DEMO}.md
  gradle/libs.versions.toml
  engine/src/{main,test}/kotlin/dev/dejalango/engine/
  app/src/main/kotlin/dev/dejalango/app/{platform,service,ui,hud,di}/
  app/src/main/assets/{silero_vad.onnx,hud/,prompts/}
  app/src/litertlm/kotlin/dev/dejalango/app/platform/llm/litertlm/
  app/src/debug/assets/fixtures/
  app/src/metavr/AndroidManifest.xml
  scripts/
```

## 11. Decisions and open checks

- Use `dev.dejalango` throughout packages, application identity, storage names and helper defaults.
- Keep English/Mandarin as the default pair, two-stage replies as the default pipeline, CPU audio
  encoding and optional constrained JSON.
- Use the phone bench and browser HUD/simulator as the implemented displays. Dedicated USB-C,
  Android XR and Horizon spatial renderers remain future work.
- Distribute weights by model download and `scripts/push_models.sh` into internal storage; retain
  fake implementations for development.
- Audio payloads, Tensor runtime initialization, embeddings and fixture turns through the app are
  verified on a Pixel 11 Pro XL. Rehearse live turns, with a microphone, before claiming a successful
  device demo.
