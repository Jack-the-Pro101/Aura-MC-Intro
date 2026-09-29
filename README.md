# Titlescreen

Note: This whole thing is basically vibe coded. I am experimenting with AI stuff.

---

A Fabric mod for Minecraft **1.21.10, 1.21.11, 26.1, 26.2 and 26.3** that replaces the vanilla loading/title screen presentation with a
configurable **video intro** - typically one baked clip that covers the whole start-up:

- the video is drawn behind the vanilla loading bar from the moment the window appears,
- the loading scene **freezes on a configurable frame** of that video (`holdAtMs`) and waits for the game
  to finish loading, then the intro simply **continues from exactly that frame** - one player, one decoder,
  no re-open, no stutter,
- the vanilla progress bar fades away on its own configurable timetable while the **MOJANG logo stays**,
- the loading overlay is unloaded at a configurable **timestamp in the video**,
- the video (alpha channel + audio supported) is composited on top of everything,
- the title screen buttons **fade in at a configurable video timestamp**,
- the **version/mod-count line and the splash text** fade in on their own configurable timestamp, and the
  **Realms notification badge** fades in on the buttons' timetable from the moment it can be seen (its sprites
  are drawn without any alpha of their own, and the overlay they live in is only drawn once vanilla's fade
  finished, so vanilla pops it in),
- the buttons get their own **vertical offset** and their own **GUI scale**, independent of the rest of the UI,
- when the video ends it can **loop a region**, **fade back to the vanilla panorama** or **freeze** on the last frame,
- the vanilla "MINECRAFT" wordmark can stay hidden while the video plays and **starts fading in the moment the
  video ends** (`fadeInAfterVideo`, on by default), so it cross-dissolves with the video's fade-out,

Everything is configurable in-game through **Mod Menu → Config** (a [Cloth Config](https://modrinth.com/mod/cloth-config)
screen - Cloth Config is an external dependency and must be installed separately, see [Requirements](#requirements)).

## Requirements

1. **Minecraft 1.21.10 / 1.21.11 / 26.1 / 26.2 / 26.3 + Fabric Loader ≥ 0.19.5** (Fabric API is required).
   Each release jar declares exactly the versions it was built against, so launchers pick the right file.
2. **Cloth Config** - provides the in-game config screen and is **not** bundled with the mod jar, so
   install it ([Modrinth](https://modrinth.com/mod/cloth-config)) alongside this mod. Mod Menu
   ([Modrinth](https://modrinth.com/mod/modmenu)) is optional and is only needed to reach the screen
   through **Mod Menu → Config**.
3. **Nothing else.** The video is decoded by FFmpeg libraries that ship **inside the mod jar** (the
   prebuilt builds from the [JavaCPP presets](https://github.com/bytedeco/javacpp-presets):
   `libavformat`, `libavcodec`, `libavutil`, `libswscale`, `libswresample` - trimmed to what VP9
   playback needs, for Windows/Linux/macOS on x86-64 and ARM64). On first use they are extracted once
   into `config/titlescreen/javacpp-cache/` and reused from there on every launch. No VLC or FFmpeg
   installation is required, and sandboxed launchers (Flatpak/Snap) work without exposing anything of
   the host OS. Bundling every platform makes the mod jar large (~120 MB) - that is the price of "put
   the jar in the mods folder and it works".

   The only mod-specific tweak to the environment is one Minecraft itself benefits from: on Linux the
   mod restricts OpenAL to `pulse,alsa` from inside the game process (an existing `ALSOFT_DRIVERS` is
   never overridden), so the game's own sound also comes up inside sandboxed launchers.

   The video's sound takes the same road as the game's own sound: on Linux it plays through the
   system sound server (PulseAudio, or PipeWire's compatibility layer) via libpulse - the same
   client library and socket OpenAL uses - so both always land on the desktop's default output
   (headphones vs. speakers) and follow its switching together. This also works inside sandboxed
   launchers (Flatpak/Snap), where plain ALSA has no sound server behind it at all. Where libpulse
   is missing, Java Sound takes over with a deliberate device choice (sound-server-backed devices
   before raw hardware, which never follow the default-output setting).

3. A video file, by default `config/titlescreen/intro.webm` inside your game directory.
   **You don't have to provide one**: the mod ships its video inside the jar and plays that whenever the
   configured path is empty or has no file at it - so it works out of the box, and dropping your own clip at
   that path overrides the bundled one (`useBundledDefaultVideo` in the config turns the fallback off).
   The bundled clip is unpacked next to the config (`config/titlescreen/bundled/`) because the decoder
   opens files by real path.

## Recommended video format

Use **WebM with 8-bit VP9 video and an Opus audio track**:

```bash
ffmpeg -i input.mov -c:v libvpx-vp9 -pix_fmt yuv420p -crf 28 -b:v 0 -deadline good -cpu-used 3 \
       -row-mt 1 -c:a libopus -b:a 128k intro.webm
```

### Single baked video (loading screen + intro in one file)

Put both scenes into **one** video (the Mojang/loading part first, then the intro) and point the intro's
`videoPath` at it. Then set:

| Option                            | Meaning                                                                                                                          |
| --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `loadingBackground.useIntroVideo` | `true` — the loading screen plays that same file instead of a second one                                                         |
| `loadingBackground.holdAtMs`      | timestamp where the loading screen freezes on a frame and waits for the game to finish loading (e.g. the end of the Mojang part) |

The loading screen plays from 0 to `holdAtMs`, **holds that exact frame** while the game finishes
loading, and the intro then **continues from that same frame** — no gap, no second file, no
transparency tricks. All `timing` values stay relative to the first frame visible on the title screen,
and the loading scene keeps its own look via `loadingBackground.fadeInMs` / `opacity` / `fit`.

Because the whole thing is opaque, nothing here depends on alpha support: it is a single ordinary
video (VP9/Opus WebM with the minimal libraries; anything FFmpeg can decode when the full
bytedeco libraries are in use).

- **Resolution**: frames are decoded at the video's native size and converted to RGBA by FFmpeg's own
  scaler (`swscale`) on the decode threads, which use every core through VP9 frame threading. The cost
  per displayed frame is a single GPU upload: the render thread hands the converted buffer straight to
  the device's command encoder, without the detour through Minecraft's own texture-image copy. The alpha
  handling for the opaque scenes happens on the decode thread. If a 4K clip is too much for the machine
  anyway, `videoMaxFps` / `loadingBackground.maxFps` bound how often a frame is uploaded.
- **Hardware decoding boundary**: with `hardwareDecoding` on, the *decoding* runs on the GPU, but the
  finished frame is downloaded to system memory once, converted to RGBA, and uploaded to the GPU once as
  the video texture. Truly GPU-resident playback (decoding straight into a texture the game renders
  from) is not reachable from this architecture: FFmpeg's hwaccel output is a VAAPI surface, CUDA array,
  D3D11 texture or VideoToolbox pixel buffer, and importing those into Minecraft's OpenGL/Vulkan context
  needs per-platform interop APIs (EGL dma-buf export, `WGL_NV_dx_interop2`, IOSurface textures) that
  neither FFmpeg nor its Java bindings expose portably. Minecraft's Windows GL context cannot import
  D3D11 textures at all outside vendor-specific extensions, and macOS' IOSurface path needs
  unnormalized rectangle-texture sampling the GUI pipeline does not offer. Players that do zero-copy
  (mpv, VLC) own their GL context and ship that per-platform interop themselves; the win here is that
  decoding - by far the most expensive stage - runs on dedicated silicon either way.
- **Audio**: the sound comes from the video itself, so the file has to contain an audio track
  (`ffprobe intro.webm` shows the streams). The mod logs which files have no audio track, and each
  video has its own **volume** option - the loading background defaults to `0`, so raise it (e.g. to
  `100`) if you want to hear it. The loading background is preloaded silently and only gets its volume
  once it is on screen, so nothing plays before the game window appears.

### Frame rate feedback

With `debugLogging` enabled the mod reports how much of a video actually made it to the screen:

```
Loading background video ended - holding its last frame (43 frames in 2489 ms, 17 fps)
Video ended after 178 frames in 3005 ms (59 fps) - fading out over 1200 ms
```

While the loading background is running, a one-shot warning is logged if it never delivers a frame
(`Loading background video produced no frames … ms after starting`), which makes a stuck video obvious
instead of looking like a slow loading screen. A very low frame count in the reports above means the
video is being decoded slower than it plays; the loading background is also limited while the game is
busy loading.

### Nothing plays before the window

The loading background video's first frame is decoded **at client startup** (as soon as the mod is
initialised, well before the loading screen appears), so the video is on screen the moment the loading
screen shows up instead of flashing vanilla first. That preload plays **silently**: its volume is
forced to `0` and the configured volume is only applied once the video is actually on screen, so no
audio starts while the game window is still being set up. The silent preload keeps running while the
game finishes starting up, so the visible playback is **restarted at 0 ms** when the loading screen
appears - video and audio always play from the beginning, never from wherever the preload got to.
`loadingBackground.fadeInMs` fades the video in on top of the loading screen (set it to `0` for a hard
cut).

## Configuration

The config lives in `config/titlescreen.json` and is editable in-game via the Cloth Config screen
(Mod Menu integration).

**Every timing is relative to the scene it belongs to**, so the two videos can be tuned independently:

| Scene                                    | Reference point for its timings                                                                                                                                                                                              |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Loading background (`loadingBackground`) | the moment the loading screen appears and the background video starts (`fadeInMs`, `maxFps`, `waitForVideoToFinish`, `maxWaitForVideoMs`)                                                                                    |
| Intro video (`timing`)                   | the **first frame that is actually on screen** of the intro video (`videoStartDelayMs`, `videoFadeInMs`, `progressBarFade*`, `overlayUnloadAtMs`, `buttonsFadeInAtMs`, `buttonsFadeInDurationMs`, `videoFadeOutMs`, `loop*`) |

The intro timeline deliberately starts with the first displayed frame rather than when the decoder is asked
to start: starting a video takes a moment, and using the request time shifted every timing by that
amount (most visibly the button fade-in). If the video never produces a frame, the provisional timeline
from the anchor is used after a few seconds so the loading screen still clears.

### General

| Option                   | Default | Description                                                                                                                                                                                                              |
| ------------------------ | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `enabled`                | `true`  | Master switch - disables every behaviour of the mod when off.                                                                                                                                                            |
| `suppressMenuMusic`      | `true`  | Keep Minecraft's menu music quiet while the video is playing, so it cannot talk over the video's own soundtrack. The music returns when the video has ended.                                                             |
| `fadeInAfterVideo`       | `true`  | Keep the "MINECRAFT" wordmark hidden while the video plays, and start fading it in the moment the video ends - so it cross-dissolves with the video's own fade-out. Off leaves the wordmark exactly as vanilla draws it. |
| `hideSplashText`         | `false` | Hides the rotating yellow splash text.                                                                                                                                                                                   |
| `debugLogging`           | `false` | Logs the intro state machine (useful while tuning timings).                                                                                                                                                              |
| `replayOnResourceReload` | `false` | Replays the intro on resource reloads (F3+T, resource pack changes).                                                                                                                                                     |
| `useBundledDefaultVideo` | `true`  | Play the video bundled inside the mod when the configured file is missing - the video works with no setup, and a file at that path overrides the bundled one.                                                            |

### Video

| Option              | Default                         | Description                                                                                                                                                                                                                                                                                        |
| ------------------- | ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `videoPath`         | `config/titlescreen/intro.webm` | Video file, relative to the game directory.                                                                                                                                                                                                                                                        |
| `videoVolume`       | `100`                           | Audio volume, 0-100.                                                                                                                                                                                                                                                                               |
| `audioDelayMs`      | `0`                             | Shifts a baked video's sound relative to its picture in milliseconds. Positive plays the sound later. Only used when `audioInSamePlayer` is off - with one player for both streams they share a clock and need no delay. Tune by ear; a faster machine needs less, or a negative value.            |
| `audioInSamePlayer` | `true`                          | Play the sound inside the video player: one clock, so no drift and no delay needed, and the decoder keeps the sound on time by dropping late video frames. Off = a separate audio-only player, which a slow video pipeline cannot starve; that player is also resynchronised to the picture automatically whenever it falls behind or runs ahead by more than a fraction of a second. |
| `videoAudioDevice`  | *(empty - automatic)*           | Output device the video's sound plays through. Empty follows the system's default output - the same one the game's own sound uses. On Linux this is a sink name as shown by the desktop's audio widget (e.g. the name of your headphones); only when the system sound server is unreachable is it a Java Sound mixer name instead (the log lists them). |
| `videoOpacity`      | `100`                           | Extra opacity multiplier on top of the video's alpha.                                                                                                                                                                                                                                              |
| `videoFit`          | `COVER`                         | `COVER` (crop), `CONTAIN` (letterbox) or `STRETCH`.                                                                                                                                                                                                                                                |
| `videoMaxFps`       | `60`                            | Upper bound for GPU texture uploads per second, `0` = unlimited.                                                                                                                                                                                                                                   |
| `hardwareDecoding`  | `true`                          | Decode the video on the GPU: D3D11VA on Windows, VideoToolbox on macOS, VAAPI (Intel/AMD) or NVDEC (NVIDIA) on Linux. Needs the GPU vendor's driver installed; falls back to software decoding automatically when no working decoder is found.                                                     |

### Timing (relative to the intro video, measured from its first displayed frame)

| Option                      | Default                | Description                                                                                                                                                                          |
| --------------------------- | ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `videoStartDelayMs`         | `0`                    | Delay between finishing loading and the first video frame.                                                                                                                           |
| `videoFadeInMs`             | `400`                  | Video fade-in duration. Only used when the loading screen does not reuse the intro video.                                                                                            |
| `progressBarFadeStartMs`    | `200`                  | Video timestamp at which the progress bar starts fading.                                                                                                                             |
| `progressBarFadeDurationMs` | `800`                  | How long the progress bar takes to disappear.                                                                                                                                        |
| `overlayUnloadAtMs`         | `1400`                 | Video timestamp at which the loading overlay (MOJANG logo) is unloaded. `0` = vanilla transition.                                                                                    |
| `overlayUnloadStyle`        | `INSTANT`              | `INSTANT` drops the loading scene at the timestamp; `VANILLA_FADE` uses vanilla's two second cross-fade underneath the video.                                                        |
| `buttonsFadeInAtMs`         | `900`                  | Video timestamp at which the buttons start fading in.                                                                                                                                |
| `buttonsFadeInDurationMs`   | `1000`                 | How long the buttons take to fade in.                                                                                                                                                |
| `textFadeInAtMs`            | `900`                  | Video timestamp at which the version/mod-count line and the splash text start fading in (fully transparent before it). The Realms badge fades in from the moment it appears instead. |
| `textFadeInDurationMs`      | `1000`                 | How long those texts take to fade in (also the Realms badge's fade, and the wordmark's fade after the video). Set it equal to `videoFadeOutMs` for a symmetric cross-dissolve.       |
| `videoFadeOutMs`            | `1200`                 | Fade used by `FADE_OUT_TO_PANORAMA`.                                                                                                                                                 |
| `endBehaviour`              | `FADE_OUT_TO_PANORAMA` | `LOOP_REGION`, `FADE_OUT_TO_PANORAMA` or `FREEZE_LAST_FRAME`.                                                                                                                        |
| `loopStartMs` / `loopEndMs` | `0` / `0`              | Loop region for `LOOP_REGION`; `loopEndMs = 0` means end of video.                                                                                                                   |

### Layout

| Option                 | Default | Description                                                                      |
| ---------------------- | ------- | -------------------------------------------------------------------------------- |
| `buttonsYOffset`       | `0`     | Moves the buttons down (positive) or up (negative) in GUI pixels.                |
| `buttonsGuiScale`      | `-1`    | GUI scale for the title screen buttons only; `-1` follows the vanilla GUI scale. |
| `useVanillaButtonFade` | `false` | Keep vanilla's two second fade and ignore the button timings.                    |

### Loading screen background

| Option                                     | Default                                      | Description                                                                                                                                                                                         |
| ------------------------------------------ | -------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `loadingBackground.enabled`                | `true`                                       | Play a video behind the vanilla loading bar instead of the plain red background.                                                                                                                    |
| `loadingBackground.videoPath`              | `config/titlescreen/loading_background.webm` | Loading background video. Only used when "reuse the intro video" is off.                                                                                                                            |
| `loadingBackground.useBundledDefaultVideo` | `true`                                       | Play the loading clip bundled with the mod (`assets/titlescreen/video/mojang_studios.webm`) when the configured file is missing. Only used when "reuse the intro video" is off.                     |
| `loadingBackground.volume`                 | `0`                                          | Audio volume (muted by default). Only used when "reuse the intro video" is off — with a baked video the loading scene plays part of the intro, so the intro's own volume applies to the whole clip. |
| `loadingBackground.opacity`                | `100`                                        | Opacity multiplier.                                                                                                                                                                                 |
| `loadingBackground.fit`                    | `COVER`                                      | `COVER`, `CONTAIN` or `STRETCH`.                                                                                                                                                                    |
| `loadingBackground.fadeInMs`               | `500`                                        | Fade-in once the loading screen appears.                                                                                                                                                            |
| `loadingBackground.maxFps`                 | `30`                                         | Upload throttle for the background video.                                                                                                                                                           |
| `loadingBackground.hideVanillaLogo`        | `true`                                       | Hides the vanilla MOJANG STUDIOS logo while the video is showing.                                                                                                                                   |
| `loadingBackground.useIntroVideo`          | `true`                                       | Use the intro video for the loading screen too - one file with the loading part first and the intro after it. One player, one decoder, no hand-over. Off = use the separate file above.             |
| `loadingBackground.holdAtMs`               | `0`                                          | Frame of that video at which the loading scene freezes and waits for the game to finish loading; the intro continues from exactly that frame. `0` plays the whole clip during loading.              |
| `loadingBackground.loop`                   | `false`                                      | Off = hold the last frame, which leaves a static background for the loading bar.                                                                                                                    |
| `loadingBackground.replayOnResourceReload` | `true`                                       | Replays the background on resource reload splashes.                                                                                                                                                 |
| `loadingBackground.waitForVideoToFinish`   | `true`                                       | Keeps the loading screen up until the clip has played to the end, so the next scene (intro/title) never starts mid-clip.                                                                            |
| `loadingBackground.maxWaitForVideoMs`      | `30000`                                      | Safety net for the option above: continue loading after this long even if the video never ends (`0` = wait forever).                                                                                |

### Audio cuts in and out while the game is loading

In a baked setup (`loadingBackground.useIntroVideo: true`) the sound is played by its **own audio-only
player**, so a slow video pipeline cannot starve it. If you still hear gaps, `debugLogging: true` prints how
fast the video really plays (`Loading video is playing at N fps`) - and since the audio is independent now,
a low number there means the picture is dropping frames, not that the sound is at risk.

A source that is too heavy for the machine still costs frames, and the cheapest fix is on the file:

1. **8-bit sources are much cheaper than 10-bit ones.** A 10-bit 4:2:2 clip is converted to 8-bit RGBA on
   the CPU for every frame; 8-bit 4:2:0 skips most of that work. Re-encoding keeps the audio untouched:
   ```bash
   ffmpeg -i intro.webm -c:v libvpx-vp9 -pix_fmt yuv420p -crf 28 -b:v 0 -deadline good -cpu-used 3 \
          -row-mt 1 -c:a copy -y intro_8bit.webm
   ```
2. A lower `videoMaxFps` / `loadingBackground.maxFps` does _not_ help here - the cost is per decoded frame,
   not per upload.

The picture and the sound are separate players with their own clocks whenever `audioInSamePlayer` is off, and
then their positions are equalised while both are paused: at the freeze frame both are seeked onto exactly
`holdAtMs`, and when the loading scene starts the sound is seeked to the same position as the picture. Doing
it while paused means it cannot be heard, and afterwards both run in real time from the same frame. The
remaining difference is the audio output's own re-fill after such a seek - tens of milliseconds, constant
rather than growing - and `video.audioDelayMs` shifts the sound by a fixed amount on top of it if that is not
enough. With the default (one player for both streams) all of this is moot: there is one clock.

While playing, the two clocks are also watched: if the sound holds a gap of more than ~250 ms behind the
picture (or ~750 ms ahead of it) for longer than a hiccup, it is seeked onto the picture's position. This
closes the one failure mode the open-loop design had: the first launches of a heavy modpack stall every
thread for seconds at a time (JIT, cold caches, native library extraction), and whatever stalled used to
stay behind for the rest of the playback - heard as the sound lagging the picture terribly.

Inside one player the picture is paced against a single playback clock whose master is wall time: pauses
freeze it, seeks re-anchor it, and it can neither stall nor race with whatever the sound output reports.
An output whose device position can be fully accounted for (Java Sound's `SourceDataLine`) hands the clock
to the sound instead, gluing picture and audio together sample-tightly; a sound server's reported latency
cannot be trusted that far (after an underrun PipeWire's PulseAudio layer reports `0` forever), so there
the sound plays best-effort under the wall clock. The demuxer additionally keeps ~400 ms of decoded audio
ahead of the clock before its pacing may stall - without that cushion the audio stream runs empty at the
first stutter and the sound cuts out - and when refilling it, far-ahead video frames are decoded but not
converted or shown (the same frame-dropping a too-slow decoder causes, in the other direction).

With `debugLogging: true` a heartbeat is logged every 5 s while a video is on screen (frame counters, alpha,
positions, scene flags). It exists so that a stalled scene is visible in the log: a freeze that leaves no
other trace is otherwise impossible to tell apart from a log that simply ended.

Note that the sound can start a hair after the picture, but no longer by a noticeable margin: the audio
output is opened **before playback** - during the silent preload, at volume 0 - instead of when the loading
scene begins. An output that already exists only has to be fed; resuming it only means turning the volume
up. Opening it late costs about a second of silence _and_ the same second of delay, which is what used to
push the sound behind the picture.

### Video never shows up, or the game seems frozen

- **The client thread never calls into the decoder.** FFmpeg calls happen on the decode and audio threads
  only; the client thread reads cheap cached values (frame counters, cached positions, container length).
  Player creation happens on background threads, teardown joins those threads with a timeout - and if one
  refuses to stop, its context is leaked rather than ever freezing the game.
- **Stalled playback is detected.** A decoder occasionally stops delivering frames (the clip just freezes
  while its audio keeps playing, or it stops right at the end and never fires its end event). Both scenes
  track uploaded frames themselves:
  - the loading screen stops waiting for the background video instead of sitting there until
    `maxWaitForVideoMs` expires (`Loading background video stopped delivering frames after … frames - continuing without it`),
  - the intro video is treated as ended so it fades out / freezes like a normally finished video.
- **A missing video is recovered.** If the intro player starts but never delivers a frame it is restarted
  (up to 3 attempts, `The video intro produced no frames … restarting it`), and a failed start is retried
  instead of disabling the video for the rest of the session.
- Start-up and teardown of the players are serialised, so the hand-over from the loading screen (frozen
  background player) to the intro video cannot interleave inside the FFmpeg contexts.
- With `debugLogging: true`, a watchdog notices a frozen client (no tick for 10 s) and writes a
  **thread dump** into the log once (`The client has not ticked for … ms - writing a thread dump`). If
  the game ever hangs, that dump identifies the stuck thread - please keep it when reporting.

### Draw order

1. Loading screen: red background → **loading background video** → loading bar (the MOJANG logo is
   hidden while the video is up).
2. After loading: **intro video** above the loading overlay's logo, and once the overlay is unloaded it
   sits above the panorama but **beneath the title screen buttons** - so hover highlights, the hand
   cursor and clicks always belong to the buttons.

The title screen is rendered _underneath_ the loading overlay, so it draws the same video layer while the
overlay is still up. That is what covers the panorama and the buttons during the overlay's fade-out - and
it is also why nothing of the title screen can flash between the overlay disappearing and the intro
taking over.

## How it works (performance notes)

- FFmpeg decodes on its own native threads (VP9 frame-threaded, using every core); the game thread copies the newest decoded frame straight into
  the texture's pixels (one copy, one upload per displayed frame, throttled by the max-FPS options) and
  draws it through the vanilla GUI render pipeline (the render-state system on 26.x, immediate
  `GuiGraphics` drawing on 1.21.x).
- Frames are kept at the video's native size and converted to RGBA by FFmpeg's `swscale` on the decode
  threads; no extra resampling pass runs in Java.
- The video is drawn with a **linear, clamp-to-edge** sampler instead of the nearest/repeat one Minecraft
  creates dynamic textures with, so scaling the clip to the screen is filtered rather than point-sampled
  (that point sampling is what made the picture look blocky and jagged next to a video player's scaler).
  The 26.x builds use their own sampler for this; on 1.21.x the GUI pipeline picks the sampler, so the
  picture is drawn the way vanilla blits any texture there.
- Everything that can be done off the render thread is: an opaque scene's alpha is dropped while the
  thread writes the frame, which is where the spare time is. Doing it per pixel on the render thread cost
  more than the rest of the frame combined at 4K, and that was what made the loading video stutter.
- The loading scene's video is **preloaded during early startup** (FFmpeg loaded before the window exists,
  first frame decoded and then paused), so it is already on screen the moment the loading screen appears.
- With a baked single video there is exactly one _video_ player and one texture for both scenes, so the
  hand-over allocates, opens and decodes nothing - and by default that same player carries the sound, which
  means picture and sound share one clock and cannot drift, and the picture always stays in one player.
- Frames produced while the render thread is busy are dropped instead of queued.
- Nothing is started on the render thread: player creation, teardown and every decoder call happen on
  background threads (see `FfmpegVideoPlayer`).
- If anything goes wrong (missing file, FFmpeg not loadable, decode error) the mod disables itself and the
  vanilla loading/title screen is used unchanged.

## Building

The project is a [Stonecutter](https://stonecutter.kikugie.dev) multi-version project: one shared source
tree in `src/`, built once per supported Minecraft version (see `settings.gradle` and the per-version
properties in `versions/<mc>/gradle.properties`).

```bash
./gradlew build            # builds the active version (26.2, see stonecutter.gradle)
./gradlew :1.21.10:build   # builds one specific version
./gradlew :1.21.10:build :1.21.11:build :26.1:build :26.2:build :26.3:build
```

Each build lands in `versions/<mc>/build/libs/` as `Titlescreen-<mod version>+<mc>.jar`. The 1.21.x
builds are remapped to intermediary names (classic Fabric tooling, `fabric-loom-remap`), while the 26.x
builds compile directly against the unobfuscated jars (loom's non-obfuscated mode). Version-specific
API differences are handled with Stonecutter comment conditions (`//? if ...`) and name swaps in
`gradle/common.gradle`, so the same sources build everywhere.

## Jar size

The mod jar is large (~120 MB) because it bundles the prebuilt FFmpeg libraries for all five supported
platforms (Windows/Linux/macOS, x86-64 and ARM64). The build already does everything possible with the
prebuilt libraries:

- Each platform's natives jar is trimmed to the libraries the player loads - `avutil`, `avcodec`,
  `avformat`, `swresample`, `swscale` plus their JavaCPP JNI wrappers. Everything else in the jar
  (`avdevice`, `avfilter`, the `ffmpeg`/`ffprobe` command line programs, GraalVM native-image metadata)
  is stripped, and what remains was verified against each library's actual `NEEDED` dependencies
  (`libavcodec` hard-links `libva`/`libavutil`/`libswresample`; `libavformat` links `libavcodec`;
  everything else is a system library) - nothing kept can be removed, and nothing removed was loadable
  without the rest.
- The nested jars are written with maximum deflate; library payloads are stripped machine code and do
  not compress further (measured: deflating harder saves kilobytes).

What remains is the codecs themselves: `libavcodec` alone is 35 MB (25 MB compressed) because a prebuilt
FFmpeg contains _all_ codecs and every encoder, and a shared library cannot be slimmed after the fact.
Playing one VP9 clip needs a fraction of that - which is what the **custom minimal build** is for:

## Minimal FFmpeg build (GitHub Actions)

`.github/workflows/ffmpeg.yml` builds a minimal FFmpeg (`--disable-everything`, only the Matroska
demuxer, VP9/Opus decoders and parsers, the file protocol, swscale and swresample - no encoders, no
network) **with VP9 hardware decoding included** (VAAPI on Linux - plus NVDEC on x86-64 -
D3D11VA/DXVA2 on Windows, VideoToolbox on macOS) for all five platforms: Linux x86-64 natively and
ARM64 cross-compiled on Ubuntu, Windows x86-64 through mingw-w64 on Ubuntu, and macOS ARM64
natively plus x86-64 cross-compiled on the Apple Silicon runner. The downloaded tarball and the
build outputs are cached, and the staged libraries are uploaded as workflow artifacts (kept 90
days) - GitHub packs an artifact download into exactly the zip that gets committed. The workflow
is **manual-only** (`workflow_dispatch`) - the libraries rarely change.

Because the minimal build is made from the **same FFmpeg release** the JavaCPP preset targets, the
public ABI is identical and bytedeco's JNI wrappers work against it unchanged - only the fat codec
payloads are gone. The zips come out at ~5 MB per platform instead of ~23 MB, shrinking the mod jar to
roughly **40-50 MB** with all five platforms bundled.

**Using the results:** run the workflow from the Actions tab, download the five zips from the finished
run's Artifacts section, drop them into `gradle/ffmpeg/libraries/` **and commit them** - that folder is
the default of the `ffmpeg_libraries` property in `gradle.properties`, so every later build (local and
CI) bundles the committed libraries with no configuration:

```
gradle/ffmpeg/libraries/
  ffmpeg-8.1.2-min-linux-arm64.zip
  ffmpeg-8.1.2-min-linux-x86_64.zip
  ffmpeg-8.1.2-min-macosx-arm64.zip
  ffmpeg-8.1.2-min-macosx-x86_64.zip
  ffmpeg-8.1.2-min-windows-x86_64.zip
```

The build substitutes those libraries for the bytedeco ones while nesting (per platform; platforms
without a zip fall back to the trimmed bytedeco build with a warning, so a fresh clone without the
committed zips still builds). A different folder works too, via `-Pffmpeg_libraries=<folder>` - it
accepts `ffmpeg-*-min-<platform>.zip` / `<platform>.zip` files or plain `<platform>/` subfolders.
`gradle/ffmpeg/build-minimal.sh` is the script behind the workflow and can also be run locally
(`bash gradle/ffmpeg/build-minimal.sh linux-x86_64`); it is what keeps the CI recipe and local builds
identical.

## Licensing note

The mod bundles [JavaCPP](https://github.com/bytedeco/javacpp) and its FFmpeg preset plus JNA via
jar-in-jar so that the video feature works out of the box. The nested FFmpeg libraries are the LGPL-2.1
builds from the JavaCPP presets, trimmed to the libraries the player actually uses (`avutil`, `avcodec`,
`avformat`, `swresample`, `swscale` - `avdevice`, `avfilter` and the command line programs are stripped
by the build). FFmpeg is licensed under LGPL-2.1-or-later (source and license: https://ffmpeg.org),
JNA is dual-licensed Apache-2.0/LGPL-2.1. Building with `bundle_video_libraries=false` in
`gradle.properties` produces a jar without them, where the video then needs those libraries on the
classpath some other way.
