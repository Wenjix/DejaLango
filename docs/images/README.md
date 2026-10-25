# README visuals

The three phone images are unedited captures of the native Android `CompanionWalkthroughActivity`. They use authored, synthetic travel dialogue from [travel-scenarios.json](travel-scenarios.json), rather than model inference or a recorded trip. The native captures were made from app UI at commit `66818e2`.

| File | Scenario and state |
|---|---|
| [native-beijing.png](native-beijing.png) | Meeting someone in Beijing. Simplified Mandarin with pinyin; reply suggestions, fixture 1 / beat 2. |
| [native-san-francisco.png](native-san-francisco.png) | Asking directions in San Francisco. The wearer speaks Mandarin (Taiwan), the partner speaks English (US); Mandarin question to English variants, fixture 2 / beat 12. |
| [native-colombia.png](native-colombia.png) | A takeaway request in Colombia. Your English request with Spanish phrasing, fixture 0 / beat 12. |

[dejalango-hero.png](dejalango-hero.png) is a **1440 × 940** browser capture of [dejalango-hero.html](dejalango-hero.html), combining those three native images without editing their content. [dejalango-lens.png](dejalango-lens.png) is a **600 × 600** browser capture of [dejalango-lens.html](dejalango-lens.html). The green lens is an illustration of the same authored Beijing conversation, not a capture of connected glasses.

For native capture, each scenario's lines replace the matching own-language and target-language entries in the debug fixture catalog on an isolated host build. The selected fixture's label supplies its destination and situation. The San Francisco capture temporarily adapts the debug fixture loader to support an English-speaking partner and Mandarin-speaking wearer; the production UI components already support this pair. Build the debug walkthrough with inference runtimes disabled, launch its fixture and beat on an explicitly selected emulator, then capture its screen. Restore both the original fixture catalog and any temporary debug-loader adaptation byte-for-byte after building. No app source changes or weights are part of this documentation update.

To update either composite, serve the repository locally, open its HTML source in a browser at the size above, wait for local fonts and images, and export the `main` element as PNG. Keep the synthetic and illustration labels visible.

The café scene and local fonts come from existing app assets. Their origins and licenses are recorded in [the app's asset notes](../../app/src/main/assets/companion/ORIGINS.md) and [third-party notices](../../THIRD_PARTY.md).
