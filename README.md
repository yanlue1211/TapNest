# 点点栖 · TapNest 🌱

**轻轻一点，栖在身边。**

TapNest is an Android floating desktop-pet app derived from
[DeskPet](https://github.com/faysal-X-N/deskpet), licensed GPL-3.0-or-later.

## Development snapshot · TapNest layered companions (not yet a stable release)
- New app name and independent Android application ID: `com.tapnest.pet`.
- No bundled pet art. Import your own local PNG, GIF or Codex Pet packages.
- New single-image PNG mode supports on-screen floating, dragging, resizing and
  a tap-to-bounce interaction with no image generation.
- Existing GIF and Codex Pet import and overlay mechanisms are retained.
- Local layered character creation on Android: a built-in wing/bow/tail/head template, reusable custom templates, or a fully manual rig. Head/body/face/wing frames composite into the actual character.
- Live multi-layer preview during creation: choose a layer, drag it to reposition, and adjust its size, pivot and parent binding. Long-press a completed **layered** character in the **桌面伙伴** library to re-edit it without changing its ID or preset dialogues.
- Tapping swaps expressions temporarily, then returns to neutral automatically. For layered characters, long-press and stroke horizontally across the upper head region to trigger a head rock and optionally a dedicated petting expression.
- Floating artwork starts smaller; scaling can go down to 20%. Layer rendering uses three rotating bitmap buffers to avoid drawing a frame while it is being cleared.
- Per-pet **offline preset speech bubbles**: click the 💬 button on a pet card to edit lines (one per row), toggle speech on/off, and save. Tapping the floating pet cycles through the stored lines for around 3 seconds. No AI.
- No network permission, AI model, API configuration or online upload.

**Not implemented yet:** a visual keyframe timeline, arbitrary touch-region editor,
`.tapet` character packages and in-app AI. This remains an unsigned development build:
CI green verifies compilation/tests, not every Android or HarmonyOS phone's runtime.

## Build and test
Android 8+; JDK 17+ and an Android SDK.

```sh
./gradlew testDebugUnitTest assembleDebug
```

A GitHub Actions workflow on `main` also runs tests, lint and produces an
`app-debug` APK artifact if the build passes. This is a development artifact,
not a signed public release.

## Privacy and ownership
Character files stay on-device in private application storage. No account, image
generation or LLM is involved. Any future AI companion function must be a
**separate opt-in module**, off by default.

## Credits and licensing
Source based on DeskPet; original Git history and LICENSE are preserved. Refer
to [TAPNEST_NOTICES.md](TAPNEST_NOTICES.md) for distribution requirements.
All upstream example pet art and branding resources are removed from the current
working tree, including old store screenshots; Git history remains for attribution.
