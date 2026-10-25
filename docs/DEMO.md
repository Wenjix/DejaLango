# Companion demo and validation

The native Companion Soft press app replaced the bench flow on October 24. That day the demo phone still ran the earlier build, whose measurements are in [FINDINGS.md](FINDINGS.md); do not present the native walkthrough as a measurement of that build.

## Native walkthrough

Use an explicitly selected emulator and the preview APK from the README's "Build and preview" section. Open Settings → Developer tools → Open synthetic walkthrough. It requests no microphone, runs no inference, and never saves a profile.

1. Choose Colombia. “Next beat” moves through their question, replies, Talk, speech, word help, Done, capture closure and recognition.
2. Tap Talk and Done individually. Show that the fixed controls remain on Today, Live and Your voice, and distinguish the glasses display pause from listening pause.
3. At the merged-line beat, press its correction control. Show the exact moved words and Undo.
4. Try Mainland and Taiwan. Characters and pinyin appear together. UK English is a separate regional choice.
5. Show the optional partner cue waiting until recognition. An own-language aside waits until its translated variant is spoken.
6. On Today, the café entry is explicitly a future practice concept. Its language choices are disabled; no lesson or personal playbook is being demonstrated.

## Real-model rehearsal after device validation is authorized

Use a full JDK 25. A default build includes LiteRT-LM and the Tensor runtime. Select a device explicitly for every install, model push and trace export. Avoid `connectedAndroidTest`, which may uninstall app data and weights.

Choose English (US) ⇄ Spanish (Colombia), start listening and check the actual model/backend in the developer bench. Ask the partner to speak, read a suggested reply after a Talk tap, and optionally press Done. Capture closure should appear immediately; recognition and use counting must wait for actual model results. Test original Spanish, an English aside and a spoken translated variant. Do not use a manual confirmation or mode switch.

For the local lens, run `ANDROID_SERIAL=DEVICE_SERIAL scripts/hud_forward.sh`, then open the current tokenized HUD link shown in the bench. Replace `/hud` with `/sim` while retaining the token to use the bundled synthetic café. Camera preview requires an explicit click. T/ArrowUp is Talk, C is Done, left/right browses, S corrects the latest line, U undoes, and P pauses the display. A restarted runtime rotates the token.

Measure repeatable restaurant fixtures, quick answers at 0/0.2/0.5/1 s gaps, early/late/forgotten Done, afterthoughts, stray taps and hint holds before choosing final timings. Report merges, clipping, wrong scripts, missing frames and model-stage times together with latency. The draft's 1.2 s silence and 600 ms Done slack are starting hypotheses.

## Playbook demo

The wearer's phone runs the app, and a second phone plays the local's seeded lines. The soundboard page and the tool that renders the lines aren't in this repository yet.

1. On the wearer's phone, set English (US) ⇄ Mandarin (Mainland) and start listening. Under Settings → Developer tools → Bench → **playbook scene**, choose the scene's chip, then wait for **playbook: ready**. The bench config should say `two_stage`, because the single shape skips the playbook.
2. Open the glasses view in `/sim`, as above.
3. Play one of the local's lines on the second phone. "Prepared" cards should appear a second or two after it ends. Tap Talk, read a card aloud, then play the next line.
4. To change scenes, press Clear and choose the next scene's chip. The next line uses it, with no re-index.

Keep these in mind:

- Use one scene's chip, not **All**. Some lines recur across scenes, and **All** mode returns them as ambiguous.
- Stay quiet near the microphone while the index builds. Each turn pauses the indexing.
- Pause with the Companion screen's Stop, not the bench's. Restarting listening rebuilds the index and rotates the `/sim` token.
- Show the audience the glasses view or `/sim`. The Companion screen labels prepared cards as your own replies.
- An unscripted line goes to Gemma, which writes "Reply" cards in a few seconds. That contrast shows the step the playbook skips.

## Recovery

| Situation | Recovery |
|---|---|
| Microphone permission denied | Enable microphone access in Android app settings, then retry listening. |
| Model missing or failed to load | Finish local model setup; retry listening. No silent canned fallback is used. |
| Explicit synthetic model | The consumer surface labels it. Stop the runtime, disable fake inference in developer tools, then restart. |
| Empty/malformed result or timeout | Keep the useful existing context; tap before another turn and try again. |
| Hint unavailable | Continue speaking or use the own-language path. Hints are optional and cannot block capture indefinitely. |
| Split audio expired | Move the whole line or Undo. No fabricated split text is shown. |
| Browser link stopped working | Reconnect forwarding for the selected device and reopen its current tokenized link. |
| Prepared cards don't appear | Check the bench: **playbook: ready**, the right scene chip and `two_stage`. Lines spoken before the index is ready go to Gemma. |

Hardware inference, microphone timing, one-handed reach, optics and physical haptics remain separate validation work for this candidate.
