# Fixtures

Drop 16 kHz mono 16-bit WAV files here (other rates are resampled, nearest-neighbour). The bench's
"replay fixture" knob lists `demo_zh.wav`; add more names in `BenchScreen.kt` or pick them from the
settings. A fixture plays through the identical pipeline the microphone feeds — VAD, segmentation,
Gemma — so it is the latency benchmark and the fallback for a loud room.

Suggested `demo_zh.wav` (record it this week, ~60 s): a Mandarin speaker asks four or five short
questions with ~1.5 s gaps, then an English speaker answers one of them with a suggested reply.
