# Findings: EmbeddingGemma 2 and the Tensor TPU

Measured on 2026-10-23 on one Pixel 11 Pro XL (Tensor G6) with LiteRT-LM 0.18.1. Each figure comes from one to
three runs. Treat them as indications, not benchmarks, and rerun before quoting them.

## Summary

- **Gemma 4 E2B and EmbeddingGemma 2 now run on the TPU together.** Before these fixes, neither did.
- **Retrieval had never used a real embedding on the phone.** The installed embedder was Intel's NPU build, so
  the app silently fell back to hashed character trigrams.
- **Gemma failed on the TPU because of where its file lived, not because of the model.** From internal storage,
  the TPU loads Gemma in about 1.4 s, against 12.5 s on the GPU, and Stage A takes 2.2–2.7 s instead of 4.1 s.
- **Through the whole app, the TPU brings replies about 3 s sooner.** On the demo fixture with the same build, the
  median from turn end to replies is 7.1 s on the TPU and 10.1 s on the GPU; to the transcript, 1.9 s and 3.6 s.

## What was wrong, and what changed

| Problem | Evidence | Fix |
|---|---|---|
| Wrong embedder file | `models/embeddinggemma-2-text.litertlm` was byte-identical (SHA-256 `048e219d…`) to `embeddinggemma-2-text-270m_Intel_PTL.litertlm`. `fetch_models.sh` downloaded all 11 builds. Its skip patterns were lowercase (`*_intel_*`) while the files say `_Intel_`, and on macOS bash the generic file sorts last. | `fetch_models.sh` fetches the generic and the variant's build by name, never renames, and fails if a file doesn't arrive. `ModelFiles` never takes a vendor build as the generic one. |
| Silent fallback | Retrieval took ≤ 6 ms on 67 of 69 turns; a real model on CPU takes about 58 ms. The fallback logged nothing. The bench showed only a file name. | The embedder logs every attempt, and the bench line shows what it runs on, e.g. `embedder=embeddinggemma-2-text-270m_Google_Tensor_G6 · NPU`. |
| Gemma never on the TPU | `preadv2 failed at offset 1123255072 … Operation not supported on transport endpoint [95]`, then `Failed to get SB invocation context` and `DISPATCH_OP failed to prepare`. `AUTO` fell back to the GPU. The models lived in `/storage/emulated/0/Android/data/…`, which is FUSE. | `push_models.sh` streams models into the app's internal storage (`files/models`, f2fs) through `run-as`. `ModelFiles` reads that folder first and the old one second. |
| Embedder CPU-only, raw text | `Backend.CPU()` was hard-coded, and no task prompts were sent. | TPU build first through `NPU`, then the generic build on CPU. Queries and stored sentences get EmbeddingGemma's two task prompts. The engine is released on **Stop**. |

The embedder's Tensor G6 build loaded fine from the FUSE folder, so the FUSE failure is specific to Gemma's bundle.

## Measurements

### EmbeddingGemma 2 text (270M)

| | Generic build, CPU, raw text | Tensor G6 build, TPU, task prompts |
|---|---|---|
| Load | about 0.5 s on first use (from log timestamps) | 634–685 ms alone; 1.09–1.34 s after Gemma |
| Query embedding, median of 20 | 58.1 ms | 16.1–16.3 ms alone; 17.0–17.4 ms beside Gemma |
| Same query before and after a Gemma generation | – | cosine 1.0000 |

The model card lists 8.3 ms for text on the Pixel 11 Pro TPU, for a 128-token input in isolation. Our ~16 ms is
measured from Kotlin and includes the call overhead, the prompt and normalization.

**Scores.** Cosine similarity to the query "Where are you from?":

| Sentence | CPU, raw text | TPU, task prompts |
|---|---|---|
| "Which country do you come from?" (English paraphrase) | 0.968 | 0.947 |
| 你是哪里人？ (Mandarin paraphrase) | 0.869 | 0.895 |
| "I'd like a large coffee, please." (unrelated) | 0.685 | 0.652 |

- **Both paraphrases outrank the unrelated sentence in both setups.** This includes the cross-lingual one, which
  DejaLango needs, since the other person speaks Mandarin and the wearer's sentences are English.
- **The Mandarin gap widened from 0.18 to 0.24.** Two things changed at once, backend and prompts, so the change
  can't be pinned on either.
- **Unrelated text still scores about 0.65.** Cosine is not a probability, so any match threshold has to be set
  from real pairs.

### Gemma 4 E2B: Stage A on the first five seconds of `demo_zh.wav`

| | GPU (generic build) | TPU (Tensor G6 build, 3 runs) |
|---|---|---|
| Load | 12.5 s, including the failed TPU attempt | 1.0–1.5 s |
| Time to first token | 1,610 ms | 555–789 ms |
| Decode | 15.3 tok/s | 20.0–23.5 tok/s |
| Stage A total | 4.06 s | 2.18–2.70 s |

All four runs produced the same 37-token answer:
`{"heard":"你好，你是第一次来北京吗？","heard_l1":"Hello, is this your first time coming to Beijing?"}`. A short
text generation on the TPU (177-token context, 16 tokens) reached its first token in 415–881 ms and decoded at
23.1–24.9 tok/s.

### The app's turns earlier the same day (GPU)

These are 114 real-model turns from the trace CSV, across several experiments, so treat them as rough:

| Step | Median |
|---|---|
| Turn end → transcript | 4.7 s |
| Turn end → replies | 14.9 s |
| Stage B, wait for first token | 3.3 s |
| Stage B, decoding | 8.0 s |

40 of the 114 turns were cancelled before finishing. Audio turns had a median length of 2.6 s.

### The whole app on `demo_zh.wav`: GPU against TPU, same build

`FixtureRunAndroidTest` replays the 98.9 s fixture through `PipelineRuntime` with the default settings (reply mode,
`TWO_STAGE`). Only Gemma's backend differs between the runs; the embedder is on the TPU in both. One run each:

| | GPU | TPU |
|---|---|---|
| Turn end → transcript (4 turns) | 3.0–4.5 s, median 3.6 s | 1.7–3.2 s, median 1.9 s |
| Turn end → replies (4 turns) | 9.1–11.1 s, median 10.1 s | 6.8–9.2 s, median 7.1 s |
| Stage A first token (turns 2, 4, 5) | 0.87–1.94 s | 0.37–0.38 s |
| Stage B first token (turns 2, 4, 5) | 0.61–0.66 s | 0.32–0.36 s |
| Decode | 14.1–15.5 tok/s | 20.8–24.3 tok/s |
| Stage B output | 50–103 tokens | 104–119 tokens |

Both runs split the fixture into the same five turns, and both cancelled turn 3, as every earlier GPU replay did.
The TPU's first turn was slower than the rest, at 3.2 s to the transcript and 9.2 s to replies. The TPU wrote longer
replies and still finished sooner.

Most of what remains on the TPU is Stage B decoding: about 110 tokens at about 23 tok/s, roughly 4.7 s. Fewer or
shorter replies, replies shown as they are written, or déjà replies would cut into it.

**GPU caches.** LiteRT-LM names its GPU and audio caches after each model file's name, modification time and size.
A re-push changes the modification time, so the first GPU load afterwards rebuilds them: 56.7 s here, against about
12 s with a warm cache. The TPU loaded in about 1.5 s either way.

## How DejaLango can use these models

Gemma's decoding is the bottleneck, so the embedder's value is putting something useful on the glasses before
Gemma finishes. In order:

1. **Retrieval that works (done).** "Because you say" now matches meaning, including across languages, at about
   17 ms per turn on the TPU.
2. **Déjà replies: reuse your answer when a question comes back.** This needs the 270M model only. It is one way
   to build a planned feature: instant matches that keep the replies you used. The scores above can set its
   match threshold; recheck them for a Spanish pair.
   - **How it works:** when you use a reply, store it with the line it answered. When a similar line comes again,
     even in other words, show the old answer as soon as the transcript lands, marked "you said this before", while
     Gemma writes fresh options. On the TPU that is about 1.9 s after the turn, against about 7 s for Gemma's
     replies.
   - **The data already exists:** picked replies are stored in Mandarin (`Source.PICKED_REPLY`), but retrieval only
     reads English rows, so nothing reads them today.
   - **Weaker matches** can go into Stage B as Mandarin examples of the wearer's voice.
   - **Risk:** near-miss questions such as "where are you from?" and "where are you going?" score close together,
     and unrelated text already scores about 0.65. Calibrate the threshold on real pairs, use the
     `sentence similarity` prompt, and consider 768-dimension keys for this one index.
   - **Demo:** judge A asks 你是哪里人?, and you pick a reply. Judge B asks 你从哪儿来的?, and your answer is up
     before Gemma has finished.
3. **The 740M model's audio: test it before building.** Its Tensor G6 build is on the phone, and LiteRT-LM 0.18.1
   has `InputData.Audio`.
   - **Payoff:** the déjà match could run on raw audio when a turn ends. The model card lists 193.5 ms for a 10 s
     clip, and turns are 2.6 s long, so a match would arrive before Gemma's transcript. It would also give `SINGLE`
     fresh retrieval; today it reuses the previous turn's.
   - **Unknowns:** how well Mandarin speech matches text, and who spoke. Without a transcript we don't know, and a
     reply repeats the question's wording, so it can match the question.
   - **Test:** embed the fixture's turns and compare each against its transcript, its translation and unrelated
     sentences.

What we'd skip:

- **Matching a spoken reply to the cards by meaning.** Meaning makes the false matches in ARCHITECTURE §4.2 worse;
  edit distance is the right test for "did I read this card".
- **"Sounds like me" scoring, or voice cards built from embeddings.** These models are trained on meaning.
- **Telling the wearer from the other person by audio embedding.** The model card makes no claims about speaker
  identity.
- **Vision (440M).** The phone has no camera path, and the simulator keeps the camera in the browser on purpose.

## Open checks

- Tap **wipe**, then **seed voice**. The stored exemplars were embedded by the hashing fallback, and seeding skips
  sentences already indexed, so seeding alone keeps the old vectors.
- Replies still land about 7 s after a turn, against the 3 s target in ARCHITECTURE §6.
- Thermal behavior over a three-minute demo, and memory use with both models resident, are unmeasured.
- `Backend.GOOGLE_TENSOR()` (native `GOOGLE_TENSOR_ARTISAN`) is a second TPU path that was never tried. It isn't
  needed while `NPU` works.
- Which retrieval prompt works best, `search result` or `sentence similarity`, is untested.

## How these were measured

`EmbedderAndroidTest` and `GemmaAcceleratorAndroidTest` load the models through the app's own `AppGraph`, log the
figures above, and assert the TPU builds win. `FixtureRunAndroidTest` replays the fixture through the whole app; it
runs only with `-e fixtureRun true`, and `-e backend GPU` picks Gemma's backend. Run all of them with `am instrument`
as the README shows, never with `connectedAndroidTest`, which uninstalls the app and deletes the pushed models. The
phone was kept awake for the replays (`stay_on_while_plugged_in 7`, restored afterwards). The trace figures come
from `scripts/pull_traces.sh dev.dejalango`.
