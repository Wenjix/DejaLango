# DejaLango

**You, in any language.** An on-device conversation assistant that helps you understand
another speaker and reply in their language, in your own voice.

The project separates a pure Kotlin conversation engine (`:engine`) from Android
adapters and rendering (`:app`). The mobile and Meta VR variants use application IDs
`dev.dejalango` and `dev.dejalango.metavr`.

Run the engine tests with `./gradlew :engine:test`.

Run `./gradlew :app:installMobileDebug` to install the Android listening bench.
The fake model is enabled by default. Start listening, run `scripts/hud_forward.sh`,
and open `http://localhost:8765/hud` to view the HUD on your laptop.

Real inference is optional: build with `-PwithLiteRtLm`, download the models with
`scripts/fetch_models.sh`, and push them with `scripts/push_models.sh`.
Model files and application data belong to the new application ID; existing installs
are separate. Real-device behavior requires device validation.
