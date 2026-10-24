# Prompt overrides

The consumer runtime uses `CompanionPrompts` and `CompanionSchemas`. Its role-specific transcription,
hint, compact system and single-partner prompts are Kotlin contracts. The existing `stage_b` and
`say_it_my_way` overrides still shape reply writing, with regional names substituted. `transcribe`
and `voice_card` still support explicitly initiated owner-profile work. The older `system`,
`stage_a`, `single` and `word` templates below remain for legacy processor tests; changing them does
not change the Companion role contract.

Companion request tags are `transcribeThem`, `transcribeYou`, `stageB`, `sayIt`, `hint`, and the
developer-only partner `single`. Transcription has no guessed speaker field and no earlier turns or
offers. Only `stageB`, `sayIt` and `single` receive the reply system prompt. Keep the compact system
within 1,200 characters and verify all 42 regional pairs when changing it. Nullable pronunciation is
an empty string in constrained schemas, and is normalized to absent by the parser.

Any file named `<template>.txt` here replaces the Kotlin template of the same name in
`engine/.../prompts/Prompts.kt` at startup — no rebuild needed beyond re-installing the APK.
Template names: system, stage_a, stage_b, single, say_it_my_way, word, transcribe, voice_card.
Placeholders (`{{l1_name}}`, `{{voice_card}}`, …) must all be kept; an unfilled placeholder throws at
startup so you find out immediately.

`{{#name}}…{{/name}}` keeps its body only when `name` is non-blank; use it for `{{voice_card}}`,
`{{context}}` and `{{exemplars}}`, which are empty until there is a card, an earlier turn or a retrieved
sentence. A section tag alone on its line disappears with its line break. Sections can't nest.

`stage_a` gets only `{{l1_name}}` and `{{l2_name}}`: shown earlier turns or suggested replies, a 2B model
copies them into its transcript. In `stage_b`, `{{context}}` holds the turns before `{{heard}}`, not the
line being answered.
