# Third-party components

| Component | License | Use |
|---|---|---|
| Silero VAD v5 (`app/src/main/assets/silero_vad.onnx`) — © 2020-present Silero Team | MIT | Speech probability per 32 ms frame |
| ONNX Runtime (Android) | MIT | Runs the VAD |
| LiteRT-LM (Google AI Edge) | Apache-2.0 | Runs Gemma 4 and EmbeddingGemma 2 on-device |
| Gemma 4 / EmbeddingGemma 2 weights | Gemma Terms of Use | Downloaded by `scripts/fetch_models.sh`, never committed |
| NanoHTTPD + nanohttpd-websocket | BSD-3-Clause | Local HUD server |
| AndroidX / Jetpack Compose / Room / DataStore | Apache-2.0 | App |
| kotlinx.coroutines / kotlinx.serialization | Apache-2.0 | Engine |
