# 点点栖 · TapNest 🌱

**轻轻一点，栖在身边。**

TapNest is an Android floating desktop-pet app derived from
[DeskPet](https://github.com/faysal-X-N/deskpet), licensed GPL-3.0-or-later.

## V0.1 · first development checkpoint (not yet a stable release)
- New app name and independent Android application ID: `com.tapnest.pet`.
- No bundled pet art. Import your own local PNG, GIF or Codex Pet packages.
- New single-image PNG mode supports on-screen floating, dragging, resizing and
  a tap-to-bounce interaction with no image generation.
- Existing GIF and Codex Pet import and overlay mechanisms are retained.
- New local rig and keyframe interpolation models with unit tests. Animation preset clips for blink, nod, bounce, tail wag, ear twitch and mouth motion are now defined as local keyframes (not yet wired into a multi-layer player).
- Per-pet **offline preset speech bubbles**: click the 💬 button on a pet card to edit lines (one per row), toggle speech on/off, and save. Tapping the floating pet cycles through the stored lines for around 3 seconds. No AI.
- No network permission, AI model, API configuration or online upload.

**Not implemented yet:** multi-layer PNG rig rendering, phone-side pivot/keyframe
editor, custom touch regions and `.tapet` package support. V0.1 only plays
single-image PNG pets. Features will be added iteratively and tested before
a general release.

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
