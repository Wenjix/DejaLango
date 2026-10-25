# Third-party components

DejaLango itself is licensed under AGPL-3.0-only; see [LICENSE](LICENSE). The components below keep their own
licenses. Their full license texts are in [`licenses/`](licenses/), or next to the component where
noted.

| Component | License | License text | Use |
|---|---|---|---|
| Silero VAD v5 (`app/src/main/assets/silero_vad.onnx`), © 2020-present Silero Team | MIT | [silero-vad-MIT.txt](licenses/silero-vad-MIT.txt) | Speech probability per 32 ms frame |
| ONNX Runtime (Android), © Microsoft Corporation | MIT | [onnxruntime-MIT.txt](licenses/onnxruntime-MIT.txt) | Runs the VAD |
| LiteRT-LM (Google AI Edge) | Apache-2.0 | [Apache-2.0.txt](licenses/Apache-2.0.txt) | Runs Gemma 4 and EmbeddingGemma 2 on-device |
| LiteRT NPU runtimes: Google Tensor (every default APK), Qualcomm (`-PwithQualcommNpu`) | Apache-2.0 | [Apache-2.0.txt](licenses/Apache-2.0.txt) | Run the models on the phone's TPU or NPU |
| Gemma 4 / EmbeddingGemma 2 weights | Gemma Terms of Use | [ai.google.dev/gemma/terms](https://ai.google.dev/gemma/terms) | Downloaded by `scripts/fetch_models.sh`, never committed |
| NanoHTTPD + nanohttpd-websocket, © Paul S. Hawke, Jarno Elonen and Konstantinos Togias | BSD-3-Clause | [nanohttpd-BSD-3-Clause.txt](licenses/nanohttpd-BSD-3-Clause.txt) | Local HUD server |
| AndroidX / Jetpack Compose / Room / DataStore | Apache-2.0 | [Apache-2.0.txt](licenses/Apache-2.0.txt) | App |
| kotlinx.coroutines / kotlinx.serialization | Apache-2.0 | [Apache-2.0.txt](licenses/Apache-2.0.txt) | Engine |
| Nunito and Fredoka, local variable TTFs from the approved study assets | SIL Open Font License 1.1 | `nunito-LICENSE.txt` and `fredoka-LICENSE.txt` in `app/src/main/assets/companion/` | Native Companion typography |
| Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) | Apache-2.0 | [Apache-2.0.txt](licenses/Apache-2.0.txt); its files keep their license headers | Build |

The Companion pose sheet and synthetic café are reused from the approved local design studies.
They were generated for this project; their origins are recorded in
`app/src/main/assets/companion/ORIGINS.md`, with the pose sheet's original generation provenance.
Icons are authored geometric marks. Duolingo/Mobbin reference artwork is not included in the app.
