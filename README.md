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
