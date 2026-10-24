# Demo script

DejaLango shows a conversation transcript and replies in the other person's language, phrased in
the wearer's voice. Rehearse the following flow after real model inference works on the demo device.
The timings below are targets to measure, not verified performance.

## Before the demo

- Install and launch the app, push weights with `scripts/push_models.sh dev.dejalango`, disable the
  fake model, and restart listening. Check the model name and backend in the glass box: on a Pixel 11
  the model line says `NPU` and the status line ends `embedder=… · NPU`.
- Run `scripts/hud_forward.sh` on the laptop and open the `/sim` URL it prints (normally
  `http://localhost:8765/sim`). Grant camera permission and press F for fullscreen. Use
  `/sim?bg=photo` if no camera is available.
- Start with English as L1 and Mandarin as L2, personalized text selected, and a fresh conversation.
  Prepare an owner voice card and exemplar corpus if demonstrating personalization.
- Turn airplane mode on. Keep USB debugging connected for the local HUD and trace export.

## Three-minute flow

1. **Show the device.** Explain that the phone runs the model and sends HUD state to the laptop
   over USB. Confirm the real model name on the bench.
2. **Hear a turn.** Ask a teammate or judge to speak Mandarin. Show the Mandarin transcript and
   English translation, then two Mandarin replies with pinyin. Aim for a transcript around 1.3 seconds
   after the turn and replies around 3 seconds; report the timings actually observed.
3. **Compare phrasing.** Press P to switch phrasebook/personalized text on the same turn. Show the
   back-translation and, when retrieval returns examples, the bench's “because you say” list.
4. **Use a reply.** Press an arrow key to select it and Enter to confirm. Show the learned count.
   Confirmation records the selected reply; it does not detect whether the wearer spoke it.
5. **Try a quick voice card.** With permission, capture up to a minute of the judge speaking and
   tap **quick card (60 s)**. Their English sentences become exemplars and the card is kept in memory.
   Show the generated card and the exemplar count, then tap **wipe** to clear stored exemplars,
   cards and conversation state. To show personalization without a live sample, tap **seed voice**.
   It builds a stored card from the bundled persona corpus and indexes its sentences.
6. **Optional word-finding.** Switch to **word**, speak haltingly and pause mid-sentence. Show a
   suggested word when it passes the confidence threshold. Describe this as a separate prompt
   using the recent audio window.
7. **Show measurements.** Point to the backend and turn timings, then export CSVs with
   `scripts/pull_traces.sh dev.dejalango`.

## Failure playbook

| Symptom | Action |
|---|---|
| Model line says fake | Confirm weights were pushed to the installed application ID, build with `-PwithLiteRtLm`, disable the fake-model setting and restart listening. |
| Model line says GPU on a Pixel 11 | The Tensor bundle is missing from internal storage. Rerun `scripts/push_models.sh dev.dejalango`, then **Stop** and **Start listening**. Logcat `LiteRtLmModel` says why the TPU attempt failed. |
| Loud room prevents turn detection | Tap **Cut**. Use a prepared `demo_zh.wav` fixture if one has been installed; fixture assets are not supplied by default. |
| Replies take too long | Select one reply, reduce exemplar count, or compare the `SINGLE` pipeline. Record the result. |
| JSON parsing fails | Inspect the visible fallback text and `parseFailed` trace flag. Try the constrained-JSON setting after verifying runtime support. |
| Accelerator fails | Inspect the fallback backend and logcat's `LiteRtLmModel` output. Restart with GPU or CPU and generic weights. |
| HUD does not connect | Rerun `scripts/hud_forward.sh`; forwarding resets after reconnecting. Set `ANDROID_SERIAL` when several devices are attached. |
| Simulator camera is black | Allow localhost camera access, or open `/sim?bg=photo` or `/sim?bg=black`. |
| Quick card has too little audio | Capture another short sample and rebuild it. The rolling buffer retains up to 60 seconds. |
| No “because you say” lines | Check the exemplar count in the glass box; tap **seed voice** or build a quick card, and keep exemplars k above 0. |

## Why a glasses HUD?

The wearer can listen to the other person while reading a short line. The browser simulator
demonstrates that interaction using the same `HudState` as the phone bench. Dedicated USB-C,
Android XR and Horizon spatial renderers remain future integrations.
