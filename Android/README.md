# SameBoy for Android (Milestone 1 — Foundation)

A native standalone Android frontend for SameBoy. The portable C `Core` is reused
unmodified and driven through a thin C/JNI bridge; the app layer (activities,
`SurfaceView`, touch controls, storage) is Java. This is **M1**: it boots a Game Boy /
Game Boy Color ROM and is playable (render + audio + touch + battery saves). Later
milestones add save states, a library, settings, palettes, gamepads, peripherals, and
link cable — see `docs/superpowers/specs/2026-07-01-android-frontend-design.md`.

## Layout

```
Android/
  jni/            C/JNI bridge (reuses ../Core, unmodified)
    Android.mk    ndk-build: libsameboy_core.so = Core/*.c (17) + bridge
    Application.mk APP_ABI = all 4, APP_PLATFORM = android-26, APP_STL = none
    ring_buffer.*  SPSC audio sample ring (blocking = audio-paced emulation)
    emulator.*     owns GB_gameboy_t, callbacks, double-buffered framebuffer
    audio_aaudio.* AAudio output stream (drains the ring)
    render_gles.*  EGL/GLES2 render thread (integer-scaled blit from a locked copy)
    session.*      ties emu thread + render + audio into one running session
    sameboy_jni.c  JNI entry points (NativeBridge)
    test/          host unit tests (ring buffer, emulator-on-a-real-ROM)
  app/src/main/
    java/io/sameboy/android/  MainActivity, EmulatorActivity, EmulatorSurfaceView,
                              TouchOverlayView, SaveStore, NativeBridge
    assets/bootroms/*.bin     generated at build time from ../BootROMs/*.asm
```

## Build

Prerequisites:
- Android SDK (platform 34, build-tools 34, NDK 26.3.11579264) — point `local.properties`
  `sdk.dir` at it (see `local.properties.example`).
- A **JDK 17** for Gradle (AGP 8.5.2 / Gradle 8.9 do not run on JDK 25+). If your default
  `java` is newer, set `org.gradle.java.home` in `gradle.properties` or export `JAVA_HOME`.
- **rgbds** on `PATH` (for boot ROMs; already a documented SameBoy build dependency). If
  rgbds is absent, the build still succeeds and the app runs **boot-ROM-less** (no boot
  animation), just without the bundled boot ROMs.

```sh
cd Android
echo "sdk.dir=$HOME/Android" > local.properties      # adjust to your SDK path
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (contains `libsameboy_core.so` for
`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, plus `assets/bootroms/*.bin`).

## Host unit tests (no device required)

```sh
jni/test/run_host_tests.sh
```

Compiles and runs the platform-independent C on the build host:
- **ring_buffer** — FIFO, underrun zero-fill, blocking-pacing producer/consumer, and the
  teardown (shutdown/flush) paths.
- **emulator** — builds a synthetic ROM, runs 60 frames, asserts a 160×144 fully-opaque
  framebuffer and that audio samples were produced (draining the ring each frame).

Expected final line: `ALL HOST TESTS PASSED`.

## On-device smoke test

**Status: this build was verified on-device in Waydroid (x86_64 Android) — the app
installs, boots a ROM, renders in Game Boy Color, responds to the on-screen controls,
and streams live 48 kHz AAudio.** Verified with the open-source homebrew
[Libbet and the Magic Floor](https://github.com/pinobatch/libbet) (`libbet.gb`):

- Installs on x86_64 (native `libsameboy_core.so` loads), `MainActivity` launcher renders.
- SAF `ACTION_OPEN_DOCUMENT` picker opens; selecting the ROM launches `EmulatorActivity`.
- Boot ROM + ROM load succeed; GLES2 renders the game (integer-scaled, centered, letterboxed).
- Touch D-pad + A/B/Start/Select render AND drive the game (title → story → playable Magic Floor).
- `AudioFlinger` shows our app's AAudio track active: PCM_16 / stereo / 48000 Hz, frames served.
- No crash across the flow; app stays foreground.

Not yet exercised on-device (no blocker): **battery `.sav` persistence** — `libbet.gb` is a
ROM-ONLY cart with no save RAM; needs a battery-backed ROM to visually confirm the
load-on-open / flush-on-pause path (the synchronous-pause fix makes the flush race-free).

To reproduce on any device or emulator:
```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Then launch **SameBoy**, tap **Open ROM**, pick a `.gb` or `.gbc` file, and confirm:

- [ ] The game renders (picture, not a black screen), correctly scaled and centered
      (nearest-neighbor, aspect-correct letterbox).
- [ ] Audio plays without constant underrun crackle.
- [ ] The on-screen D-pad and A / B / Start / Select control the game; holding two
      buttons at once works and no key sticks after lifting fingers or on an
      interrupting system gesture (edge swipe / notification).
- [ ] A battery-backed game (e.g. a Zelda/Pokémon-type with an in-game save): save in
      game, leave the app (Home), reopen the ROM — the save persists. Battery files live
      under `Android/data/io.sameboy.android/files/saves/<rom>.sav`.
- [ ] Backgrounding/foregrounding and rotation resume cleanly (no crash, audio resumes).
- [ ] If the device/emulator has no audio output, video still runs (audio is a degraded
      no-op, not a freeze).

### Known M1 limitations (deferred to later milestones)
- ROM is read on the main thread; a very slow cloud SAF provider could ANR (fine for
  local / GB-sized ROMs). Background read is planned.
- No audio-device-disconnect (reconnection) handling: unplugging headphones mid-game
  stops audio until the session is restarted.
- Battery is saved on pause/backgrounding only (no periodic GB_get_battery_dirty
  polling yet): a crash or process kill while foregrounded loses progress since
  the last backgrounding.
- Only clearly-too-small files are rejected; a large non-ROM file selected via the
  */* picker boots to a black screen (the Core does no content validation).
- No save states, settings, palette editor, gamepad remapping, or link cable yet.
