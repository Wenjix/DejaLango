# Prompt overrides

Any file named `<template>.txt` here replaces the Kotlin template of the same name in
`engine/.../prompts/Prompts.kt` at startup — no rebuild needed beyond re-installing the APK.
Template names: system, stage_a, stage_b, single, say_it_my_way, word, transcribe, voice_card.
Placeholders (`{{l1_name}}`, `{{voice_card}}`, …) must all be kept; an unfilled placeholder throws at
startup so you find out immediately.
