---
name: DejaLango Companion Soft press for Android
description: The selected Sidekick world expressed through Compose Material 3, with a steady Talk and Done controller.
colors:
  light-primary: "#245795"
  light-on-primary: "#FFFFFF"
  light-primary-container: "#DCECF8"
  light-on-primary-container: "#245795"
  light-tertiary: "#1C416F"
  light-on-tertiary: "#FFFFFF"
  light-secondary: "#365642"
  light-on-secondary: "#FFFFFF"
  light-secondary-container: "#CDEBD7"
  light-on-secondary-container: "#294D39"
  light-background: "#EAF6FF"
  light-on-background: "#213D50"
  light-surface: "#EAF6FF"
  light-on-surface: "#213D50"
  light-surface-container: "#F6FCFF"
  light-surface-container-low: "#F6FCFF"
  light-surface-container-high: "#DCECF8"
  light-on-surface-variant: "#446176"
  light-outline: "#7198AB"
  light-outline-variant: "#BAD4E3"
  light-scrim: "#213D50"
  light-error: "#9F352D"
  light-error-container: "#FFE8DE"
  light-on-error-container: "#75251E"
  dark-primary: "#A9CEFF"
  dark-on-primary: "#12365E"
  dark-primary-container: "#244E7B"
  dark-on-primary-container: "#D7EAFF"
  dark-tertiary: "#6E99C5"
  dark-on-tertiary: "#102E4B"
  dark-secondary: "#B3DBC0"
  dark-on-secondary: "#153524"
  dark-secondary-container: "#284B39"
  dark-on-secondary-container: "#D4EEDD"
  dark-background: "#142936"
  dark-on-background: "#E1F0F8"
  dark-surface: "#142936"
  dark-on-surface: "#E1F0F8"
  dark-surface-container: "#1C3443"
  dark-surface-container-low: "#1C3443"
  dark-surface-container-high: "#294657"
  dark-on-surface-variant: "#B7CDDA"
  dark-outline: "#8CA9BA"
  dark-outline-variant: "#436275"
  dark-scrim: "#0C1D28"
  dark-error: "#FFB4A8"
  dark-error-container: "#602821"
  dark-on-error-container: "#FFDAD3"
typography:
  display-small: { fontFamily: "Fredoka", fontSize: "36sp", fontWeight: 500, lineHeight: "42sp" }
  headline-large: { fontFamily: "Fredoka", fontSize: "32sp", fontWeight: 500, lineHeight: "37sp" }
  headline-medium: { fontFamily: "Fredoka", fontSize: "27sp", fontWeight: 500, lineHeight: "32sp" }
  headline-small: { fontFamily: "Fredoka", fontSize: "24sp", fontWeight: 500, lineHeight: "29sp" }
  title-large: { fontFamily: "Fredoka", fontSize: "22sp", fontWeight: 500, lineHeight: "28sp" }
  title-medium: { fontFamily: "Nunito", fontSize: "18sp", fontWeight: 700, lineHeight: "24sp" }
  title-small: { fontFamily: "Nunito", fontSize: "16sp", fontWeight: 700, lineHeight: "22sp" }
  body-large: { fontFamily: "Nunito", fontSize: "16sp", fontWeight: 400, lineHeight: "24sp" }
  body-medium: { fontFamily: "Nunito", fontSize: "14sp", fontWeight: 400, lineHeight: "21sp" }
  body-small: { fontFamily: "Nunito", fontSize: "12sp", fontWeight: 400, lineHeight: "18sp" }
  label-large: { fontFamily: "Nunito", fontSize: "14sp", fontWeight: 800, lineHeight: "20sp" }
  label-medium: { fontFamily: "Nunito", fontSize: "12sp", fontWeight: 700, lineHeight: "17sp" }
  label-small: { fontFamily: "Nunito", fontSize: "11sp", fontWeight: 700, lineHeight: "16sp" }
rounded:
  line-action: "8dp"
  card: "16dp"
  turn-action: "18dp"
  controller: "24dp"
spacing:
  text-gap: "4dp"
  action-gap: "8dp"
  controller-inner: "10dp"
  line-inner: "12dp"
  controller-inline: "14dp"
  content-gap: "16dp"
  content-inline: "20dp"
  sheet-inline: "24dp"
components:
  controller-light: { backgroundColor: "{colors.light-surface-container}", textColor: "{colors.light-on-surface}", rounded: "{rounded.controller}", padding: "{spacing.controller-inner}" }
  controller-dark: { backgroundColor: "{colors.dark-surface-container}", textColor: "{colors.dark-on-surface}", rounded: "{rounded.controller}", padding: "{spacing.controller-inner}" }
  turn-active-light: { backgroundColor: "{colors.light-primary}", textColor: "{colors.light-on-primary}", typography: "{typography.label-large}", rounded: "{rounded.turn-action}", padding: "12dp 10dp", height: "60dp" }
  turn-active-dark: { backgroundColor: "{colors.dark-primary}", textColor: "{colors.dark-on-primary}", typography: "{typography.label-large}", rounded: "{rounded.turn-action}", padding: "12dp 10dp", height: "60dp" }
  turn-pale-light: { backgroundColor: "{colors.light-primary-container}", textColor: "{colors.light-on-primary-container}", typography: "{typography.label-large}", rounded: "{rounded.turn-action}", padding: "12dp 10dp", height: "60dp" }
  turn-pale-dark: { backgroundColor: "{colors.dark-primary-container}", textColor: "{colors.dark-on-primary-container}", typography: "{typography.label-large}", rounded: "{rounded.turn-action}", padding: "12dp 10dp", height: "60dp" }
  reply-light: { backgroundColor: "{colors.light-secondary-container}", textColor: "{colors.light-on-secondary-container}", rounded: "{rounded.card}", padding: "{spacing.content-gap}" }
  reply-dark: { backgroundColor: "{colors.dark-secondary-container}", textColor: "{colors.dark-on-secondary-container}", rounded: "{rounded.card}", padding: "{spacing.content-gap}" }
  conversation-light: { backgroundColor: "{colors.light-surface-container}", textColor: "{colors.light-on-surface}", rounded: "{rounded.card}", padding: "{spacing.line-inner}" }
  conversation-dark: { backgroundColor: "{colors.dark-surface-container}", textColor: "{colors.dark-on-surface}", rounded: "{rounded.card}", padding: "{spacing.line-inner}" }
---

# Design System: DejaLango Companion Soft press for Android

## Overview

**Creative North Star: "Sidekick"**

The Android companion extends the owner-selected study 07 Soft press world and the study 03 Sidekick foundation. Sky, mint, ink blue, rounded type and the existing blue/apricot speech buddies make language help feel attentive and approachable. Live gives language the space; Today gives the companions a welcoming role. Material 3 supplies the native structure, navigation and interactions.

The shared controller keeps **I’m talking** and **Done** adjacent across Today, Live and Your voice. Their emphasis follows the capture state while their labels and order stay steady. Recognition, reply generation and word help have their own visible states. The companion responds to those states without implying a grade or an identified speaker.

**Key characteristics:**

- One focused mint reply with meaning, pronunciation when relevant and visible browse boundaries.
- A compact Live buddy and shallow tactile turn controls.
- Exact correction words, destination and Undo together.
- Native scrolling, scalable text, system insets and adaptive navigation.

This is a scoped record of the implemented consumer UI. [CompanionTheme.kt](../../app/src/main/kotlin/dev/dejalango/app/ui/CompanionTheme.kt) and [CompanionScreen.kt](../../app/src/main/kotlin/dev/dejalango/app/ui/CompanionScreen.kt) supply its tokens and behavior. Frontmatter dimensions use Android **dp** and **sp**; component heights record implemented minimums. Material roles and component defaults that the code leaves unspecified remain inherited. The browser studies' pixel measurements are historical references.

The [implementation note](companion-soft-press.md) defines feature boundaries; the [verification record](verification.md) collects final checks and their scope. The [documentation report](../../.impeccable/review/companion-native/documentation.md) records inspected evidence and asset preservation. The [review verdict](../../.impeccable/review/companion-native/verdict.md) and [final navigation verdict](../../.impeccable/review/companion-native/final-verdict.md) close the seven listed findings at source/screenshot scope. Physical reach, optics, haptic feel, speech quality, latency and hardware teardown remain unmeasured by this work.

## Colors

Both static Material schemes retain the selected palette's relationships. The system dark-theme setting selects the scheme. The frontmatter records the explicit native role values; wallpaper-derived Dynamic Color is not implemented.

### Primary

Sidekick blue carries brand, actions and selected compact navigation. Pale blue primary containers distinguish capture, word help and nonprimary turn faces. Use each container with its matching on-color. The tertiary role supplies the active turn button's lower material edge.

### Secondary

Mint secondary containers group the focused reply, correction receipt and voice profile. Their on-color provides readable green ink in both themes. Expanded navigation uses the Material rail's theme-derived selection treatment.

### Neutral

Sky surface and background form the light canvas; the dark scheme uses deep blue surfaces and pale text. Paper-like surface containers hold incoming speech and the controller. Surface-variant ink carries supporting text. Outline-variant separates the controller and pair summary without adding heavy card borders.

The scrim role also supplies ink protection beneath white three-button system navigation glyphs. The activity and modal window paths both apply this protection. Gesture navigation remains transparent in source; a gesture-mode capture was not supplied.

### Status

Normal status bands use primary-container roles. Errors use error-container and on-error-container roles, with an actionable recovery message. Disabled turn faces keep primary-container color and use on-surface-variant ink. Reply browse icons use on-secondary-container with reduced opacity (.38) when disabled; custom icons inherit their control's effective content color.

**The Paired Roles Rule.** Assign colors through `MaterialTheme.colorScheme` and keep each content color paired with its container across themes.

## Typography

Local **Fredoka** supplies the wordmark, expressive headings and focused Latin reply. Local **Nunito** supplies conversation text, explanation, pronunciation and controls. The native implementation maps these faces to the Material roles in frontmatter; its focused reply uses headline-medium and its Live state heading uses title-large.

| Role | Use |
| --- | --- |
| display-small | Partner's large turn cue |
| headline-large | Today greeting |
| headline-medium | Wordmark, focused reply, word hint and major sheet headings |
| headline-small | Pair and settings headings |
| title-large | Live state, empty-state heading and partner meaning |
| title-medium | Recognized conversation line |
| title-small | Receipt heading, grouped choices and capture heading |
| body-large / medium / small | Meaning, explanation and secondary status |
| label-large / medium / small | Actions, reply position, pair labels and speaker controls |

[Font resources](../../app/src/main/res/font) are local. Each `Font` explicitly sets its variable weight axis: Fredoka uses (500); Nunito supplies (400, 600, 700, 800). Keep the axis setting alongside `FontWeight`; these bundled files otherwise default to a thin instance. Font size and line height use scalable sp units.

Chinese glyphs use Android's platform fallback. Mainland and Taiwan varieties preserve the chosen script, with pinyin beneath characters where supplied. Reply pinyin uses (16sp) with (23sp) line height; line, hint, receipt and partner pronunciation also use (16sp) with their inherited line height. Spanish receives no invented romanization.

**The Reading Order Rule.** Keep characters or spoken words first, pronunciation beneath, and meaning after them. Enlarged text wraps and scrolls while turn controls remain available.

## Layout

The root consumes safe drawing insets. A Material top app bar provides the brand, listening control and Settings; the regional pair summary sits beneath it. The body scrolls inside the remaining scaffold space. Its inline spacing and vertical rhythm follow frontmatter, with top padding (16dp) and bottom padding (24dp).

Compact widths use a Material navigation bar for Today, Live and Your voice. At an available width of at least (700dp), a Material navigation rail replaces that bar. The main scaffold stays centered within the remaining area and is bounded to (600dp), preserving a readable column on tablets.

The controller occupies the scaffold's bottom region above compact navigation. Its outside spacing is controller-inline horizontally and action-gap vertically. Talk and Done share the row in a (1.4:1) width ratio. Each face has a minimum height from frontmatter plus the lower edge (5dp); enlarged text can increase its height. Ordinary explicit action minimums are (48dp), primary full-width actions are at least (52dp), and language-choice rows are at least (56dp).

Modal sheets skip partial expansion. Pair, coaching and partner surfaces use a bounded height of (90%) of the available sheet area. The pair list and partner cue scroll within their own body; Save pair, the partner controller and Back to Live remain outside that scroll area. Coaching instructions scroll above its fixed start action. Sheet footers include the measured navigation inset and ink protection. Settings and the debug practice concept scroll as complete content surfaces.

**The Steady Controller Rule.** Growing content, tab changes and sheet bodies must preserve access to the active turn's controls. The listening session belongs to the service and shared runtime.

## Elevation & Depth

Phone cards use flat tonal surfaces. The controller adds a fine border (1dp); compact navigation explicitly uses zero tonal elevation. Modal sheets retain Material's scrim and sheet behavior. There is no custom ambient shadow vocabulary for consumer cards.

Soft press adds a lower material edge (5dp). Touch press moves the face downward (4dp) over (110ms), using `CubicBezierEasing(.23, 1, .32, 1)`. The button's Material elevation is zero in every state. The row and action labels stay in place as emphasis changes.

Keyboard input and the system Remove animations setting remove press travel. The setting is observed while the screen is active. The app requests native tick/confirm haptics for controller actions and capture closure; their physical perception was not measured.

**The Shallow Material Rule.** Keep tactile depth within the established turn-button edge and face travel. Content hierarchy comes from color, spacing and type.

## Shapes

Soft rectangles connect the native app to Companion. Content cards, turn faces, controller and small line actions use the frontmatter radii. Ordinary Material buttons, fields, chips and sheet tops retain their component defaults.

The original buddies keep rounded speech-bubble silhouettes, tiny limbs, transparent margins and soft dimensional shading. Crop their existing pose-sheet quadrants at draw time. The art's material character stays separate from flat reading surfaces.

## Components

### Shared glasses controller

A steady remote for the current conversation. The paper-like container has an outline-variant border, glasses status, independent display pause and adjacent **I'm talking / Done** actions. Talk is blue at rest; while capturing, Talk becomes pale and Done becomes blue. Done remains disabled until there is a wearer turn it can close, and disables while finishing. Disabled faces retain a visible edge and readable ink.

Display pause blanks the lens while listening continues. The top app bar separately pauses microphone listening. Done closes capture sooner; silence can also close it. Neither button promises immediate recognition or reply generation. Configurable timing hypotheses are documented in the implementation note.

### Conversation, reply and word help

Paper speech cards show a speaker correction action beside words, optional pronunciation and meaning. The speaker action has a bounded width (60–84dp) and a minimum height (48dp). It disables during wearer capture or processing.

The mint reply card presents one reply, its position, optional pinyin, meaning and attribution. Previous/next actions live on this card, disable at boundaries and use visible disabled icon colors. Reading a suggestion after Talk can count it only after recognized speech matches; there is no Use action. Actual voice profiles produce Your voice attribution; otherwise the native card says A little help.

Word help is a separate pale-blue band. Pending help shows an ellipsis; ready help shows the actual word, pronunciation where supplied and meaning. Capturing, closing, finishing, closed audio and later processing have distinct copy. The app does not display a streaming transcript.

### Exact correction receipt

A correction names what moved. The mint receipt above the conversation includes its destination, exact fragment, optional pinyin, Undo and the whole-line alternative when available. Pending correction and unavailable split audio have their own messages. Undo restores the affected state.

The lens bridge also renders the moved text and supplied pinyin independently from whether whole-line movement is available. Its existing [HUD renderer](../../app/src/main/assets/hud/hud.html) remains green on transparency, with no opaque optical panel, glow or text shadow. Both the raw HUD and simulator support S to correct the encoded latest line and U to undo the current receipt. Source and the supplied test against actual encoded HUD JSON cover these paths; optical readability was not measured.

### Pair, coaching and partner sheets

Pair selection uses Material filter chips and radio rows. It groups eight regional varieties, disables same-base combinations, swaps atomically and makes the next-turn application visible. The Save action stays below the scrolling list.

First-run coaching keeps its start CTA beneath a scrolling explanation. The partner sheet keeps the wearer's controller and Back to Live immediately available below its scrolling cue. That read-only cue invites the partner only after the wearer turn has been recognized in the target language; Done alone leaves it waiting.

### Today, Your voice and settings

Today welcomes with the larger buddy and opens Live. Consumer content reflects real state or an honest empty state. Your voice uses a Material outlined field for the owner's own sentences, at least four lines and (160dp) high, plus a full-width build action. Suggested speech never creates personal style samples.

Settings uses native controls for Catch forgotten taps and coaching, with developer tools exposed only in debug builds. The developer bench includes a one-press **Copy current HUD link** action using the native clipboard. System Back dismisses sheets and returns other consumer tabs to Today. Live headings and status changes carry polite semantics; icon-only controls have action labels.

### Companion assets and the labelled concept

[CompanionArt.kt](../../app/src/main/kotlin/dev/dejalango/app/ui/CompanionArt.kt) draws the retained artwork and original geometric icons. Icons use a round stroke (1.7 per 24-unit grid), usually displayed at (22dp), and inherit `LocalContentColor` so disabled state remains visible.

| Asset | Role and provenance |
| --- | --- |
| [Fredoka](../../app/src/main/res/font/fredoka.ttf) and [Nunito](../../app/src/main/res/font/nunito.ttf) | Byte-identical copies of study 03 local type. [Fredoka license](../../app/src/main/assets/companion/fredoka-LICENSE.txt) and [Nunito license](../../app/src/main/assets/companion/nunito-LICENSE.txt) retain SIL Open Font License 1.1. |
| [Buddy pose sheet](../../app/src/main/assets/companion/buddy_poses.webp) | Byte-identical study 07 generated art. [Original prompt record](../../app/src/main/assets/companion/buddy-poses.provenance.json) and [native sidecar](../../app/src/main/assets/companion/buddy_poses.webp.json) accompany it. |
| [Practice café](../../app/src/main/assets/companion/practice_cafe.webp) | Byte-identical prototype-generated café photograph. [Origin text](../../app/src/main/assets/companion/cafe.provenance.txt) and [native sidecar](../../app/src/main/assets/companion/practice_cafe.webp.json) retain its provenance. |

The four pose quadrants are welcome, attentive, helping and acknowledgment. Native Today/coaching use welcome, idle Live/Your voice use attentive, wearer capture uses helping, and matched speech uses acknowledgment. Expressions establish no grade, recognition confidence or speaker identity. [ORIGINS.md](../../app/src/main/assets/companion/ORIGINS.md) records the packaged asset family.

The synthetic walkthrough reuses production composables and exposes explicit fixture/beat controls. Its future café practice concept is labelled unavailable and its start choices are disabled. The café depicts an imaginary scene. Practice, trips, streaks, scoring and a semantic playbook are future product ideas.

## Do's and Don'ts

- **Do** preserve the selected Sidekick palette, local type, original buddy family and fixed Talk/Done order.
- **Do** use the native Material roles, scalable type and component states defined by the implementation.
- **Do** keep a readable column on expanded screens and use the navigation rail at the established threshold.
- **Do** keep sheet footers and system navigation legible above the measured inset.
- **Do** show correction words, destination and Undo together, preserving pronunciation where supplied.
- **Do** keep real content and clearly labelled synthetic walkthroughs distinguishable.
- **Don't** replace the selected identity while extending this native surface.
- **Don't** copy browser-study pixel sizes over native dp/sp values or remove explicit font axes.
- **Don't** let custom icon drawing bypass a Material control's disabled color.
- **Don't** turn character expressions into claims of grading, speaker identity or recognition confidence.
- **Don't** present the future vignette, configurable timings or emulator evidence as measured hardware behavior.
