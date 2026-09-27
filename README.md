# Titlescreen

A Fabric mod for Minecraft **26.2** that replaces the vanilla loading/title screen presentation with a
configurable **transparent video intro**:

- the vanilla progress bar fades away on its own configurable timetable while the **MOJANG logo stays**,
- the loading overlay is unloaded at a configurable **timestamp in the video**,
- the video (alpha channel + audio supported) is composited on top of everything,
- the title screen buttons **fade in at a configurable video timestamp**,
- the buttons get their own **vertical offset** and their own **GUI scale**, independent of the rest of the UI,
- when the video ends it can **loop a region**, **fade back to the vanilla panorama** or **freeze** on the last frame,
- the vanilla "MINECRAFT" wordmark is hidden by default (configurable).

Everything is configurable in-game through **Mod Menu → Config** (Cloth Config screen included in the jar).

## Requirements

1. **Minecraft 26.2 + Fabric Loader ≥ 0.19.5** (Fabric API is required).
2. **libVLC** installed on the system - video decoding is delegated to libVLC through
   [vlcj](https://github.com/caprica/vlcj). It is auto-detected, but *where* it must be installed differs
   per platform:

   | Platform | What you need | Why |
   | --- | --- | --- |
   | Windows | VLC media player installed | `libvlc.dll` lives in the VLC install directory; the installer records that directory in the registry, which vlcj's `WindowsNativeDiscoveryStrategy` reads. |
   | macOS | `VLC.app` in `/Applications` | `libvlc.dylib` + plugins are bundled inside the app and vlcj looks there. |
   | Linux | `vlc` (or `libvlc`) from your distro | Distros place `libvlc.so.5` in `/usr/lib64` (Fedora) or `/usr/lib/x86_64-linux-gnu` (Debian/Ubuntu) with plugins in `<libdir>/vlc/plugins`; vlcj's `LinuxNativeDiscoveryStrategy` probes those standard directories. |

   Linux is not special in *needing* a shared library - all three platforms need libVLC; Linux simply has
   no single install location convention, which is why sandboxed launchers break it:

   ### Flatpak / Snap launchers on Linux

   A sandboxed launcher (e.g. the **Flatpak PrismLauncher**, `org.prismlauncher.PrismLauncher`) cannot see
   the host's `/usr/lib64`, so the installed VLC is invisible to the game even though `dnf install vlc`
   worked. With libvlc present on the host, the error is:

   ```
   (Titlescreen/Video) libvlc could not be located. Install VLC or set 'libVLC folder' ...
   ```

   Fix it by exposing the host OS files to the launcher sandbox:

   ```bash
   # expose the host operating system (so libvlc is visible at /run/host/usr/lib64)
   flatpak override --user --filesystem=host-os org.prismlauncher.PrismLauncher
   ```

   That is all: the mod then loads VLC's libraries, VLC's plugins **and the plugins' dependencies**
   itself (by absolute path, which the dynamic loader reuses when a plugin asks for them by name), so no
   wrapper script, environment variable or other per-user setup is needed. The log says so:

   ```
   (Titlescreen/Video) Preloaded 33 libVLC plugin dependencies from /run/host/usr/lib64 (no launcher wrapper needed)
   ```

   The same applies to Minecraft's own OpenAL device inside the sandbox: the mod restricts OpenAL to
   `pulse,alsa` from inside the game process (an existing `ALSOFT_DRIVERS` is never overridden).

   > ⚠️ Exposing the host OS is a launcher-wide permission, so it applies to every instance. If you would
   > rather restrict it, `--filesystem=/usr/lib64:ro` (use `/usr/lib/x86_64-linux-gnu` on Debian/Ubuntu
   > based hosts) covers VLC's libraries alone; drop-in folders configured via `libVLC folder` work too.

   **Fallback for older setups**: if a sandbox hides the host's VLC plugin dependencies in a way the
   preload cannot reach (for example a libVLC layout with its own private ffmpeg), the mod logs the exact
   `LD_LIBRARY_PATH` to set for the *instance* (PrismLauncher: Edit -> Settings -> Environment variables,
   override global) together with the note that the sandbox's GL directories must stay in front -
   otherwise the host's GL/driver libraries shadow the sandbox's and Sodium hangs. The launcher's
   *wrapper command* is the cleanest place for that:

   ```sh
   #!/bin/sh
   GL_DIRS=/usr/lib/x86_64-linux-gnu/GL/default/lib:/usr/lib/x86_64-linux-gnu/GL/nvidia-<version>/lib
   HOST_LIB=/run/host/usr/lib64
   export LD_LIBRARY_PATH="$GL_DIRS:$HOST_LIB:$HOST_LIB/vlc:$HOST_LIB/samba:$HOST_LIB/pulseaudio${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
   exec "$@"
   ```

   > ⚠️ Restart the launcher after changing this: a launcher that is already running keeps its old
   > settings in memory and will ignore the new value.
   > ⚠️ Do **not** use `flatpak override --env=LD_LIBRARY_PATH=... org.prismlauncher.PrismLauncher`:
   > that replaces the launcher's own environment, and the launcher (libproxy) then cannot load its
   > bundled libraries - it will not even start (`dlopen() failed: libopenal.so` /
   > `libpxbackend-1.0.so: cannot open shared object file`).
   > The per-instance environment variable setting cannot be relied on either, because PrismLauncher's
   > strict-JSON `Env` value does not survive a save/load round-trip here.

   **Without the `LD_LIBRARY_PATH` entries**, VLC still loads and its plugins are found, but their
   dependencies cannot be resolved, which shows up as videos that end instantly with no picture (VLC
   falls back to its `ps` demuxer). **Without the `pulseaudio` directory** the pulse plugin fails with
   `libpulsecommon-17.0.so: cannot open shared object file` (the videos play silently, and the log fills
   with these errors), and **without `ALSOFT_DRIVERS`** Minecraft's own OpenAL device fails to open
   (`Failed to open OpenAL device`) so the game has no sound at all. The mod logs the exact variable when
   it detects the `LD_LIBRARY_PATH` condition.

   Note that the `org.videolan.VLC` Flatpak does **not** expose libvlc to other sandboxes, so installing VLC
   as a Flatpak alone is not enough. Alternatives: install PrismLauncher natively (rpm/deb), or point
   `libVLC folder` in the config at a directory containing libvlc + plugins (a matching `plugins` or
   `vlc/plugins` subfolder is picked up automatically via `VLC_PLUGIN_PATH`).

3. A video file, by default `config/titlescreen/intro.webm` inside your game directory.
   **You don't have to provide one**: the mod ships a transparent WebM placeholder (with audio) and copies
   it to that path automatically the first time, so you can just replace the file afterwards
   (`useBundledDefaultVideo` in the config turns this off).

## Recommended video format

For transparency + audio, use **WebM with VP8/VP9 video including the alpha channel and an Opus/Vorbis
audio track**. Example conversion with FFmpeg (keeping alpha):

```bash
ffmpeg -i input.mov -c:v libvpx-vp9 -pix_fmt yuva420p -auto-alt-ref 0 -c:a libopus -b:a 128k intro.webm
```

H.264 MP4 files work too, but they cannot carry an alpha channel (they will be fully opaque).

### Single baked video (loading screen + intro in one file)

Put both scenes into **one** video (the Mojang/loading part first, then the intro) and point the intro's
`videoPath` at it. Then set:

| Option | Meaning |
| --- | --- |
| `loadingBackground.useIntroVideo` | `true` — the loading screen plays that same file instead of a second one |
| `loadingBackground.holdAtMs` | timestamp where the loading screen freezes on a frame and waits for the game to finish loading (e.g. the end of the Mojang part) |

The loading screen plays from 0 to `holdAtMs`, **holds that exact frame** while the game finishes
loading, and the intro then **continues from that same frame** — no gap, no second file, no
transparency tricks. All `timing` values stay relative to the first frame visible on the title screen,
and the loading scene keeps its own look via `loadingBackground.fadeInMs` / `opacity` / `fit`.

Because the whole thing is opaque, nothing here depends on alpha support: it is a single ordinary
video (VP8/VP9 WebM, H.264 MP4, whatever VLC can decode).


* **Resolution**: the upload is capped by `maxUploadWidth` (video + loading background), and decoding is
  done in software by default — reliable, but a 4K source then plays at roughly half the configured
  frame rate (the `debugLogging` report above shows what you actually get). If you have a large *opaque*
  video and software decoding cannot keep up, `hardwareDecoding` moves the decode to the GPU — but see
  the warnings in the option table: it drops the alpha channel of VP9 alpha videos and is unreliable
  when two videos play at once.
* **Audio**: the sound comes from the video itself, so the file has to contain an audio track
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

| Scene | Reference point for its timings |
| --- | --- |
| Loading background (`loadingBackground`) | the moment the loading screen appears and the background video starts (`fadeInMs`, `maxFps`, `waitForVideoToFinish`, `maxWaitForVideoMs`) |
| Intro video (`timing`) | the **first frame that is actually on screen** of the intro video (`videoStartDelayMs`, `videoFadeInMs`, `progressBarFade*`, `overlayUnloadAtMs`, `buttonsFadeInAtMs`, `buttonsFadeInDurationMs`, `videoFadeOutMs`, `loop*`) |

The intro timeline deliberately starts with the first displayed frame rather than when libVLC is asked
to start: starting a video takes a moment, and using the request time shifted every timing by that
amount (most visibly the button fade-in). If the video never produces a frame, the provisional timeline
from the anchor is used after a few seconds so the loading screen still clears.

### General
| Option | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master switch - disables every behaviour of the mod when off. |
| `hideMinecraftLogo` | `true` | Hides the "MINECRAFT" wordmark above the title screen buttons. |
| `hideSplashText` | `false` | Hides the rotating yellow splash text. |
| `debugLogging` | `false` | Logs the intro state machine (useful while tuning timings). |
| `replayOnResourceReload` | `false` | Replays the intro on resource reloads (F3+T, resource pack changes). |
| `useBundledDefaultVideo` | `true` | If the configured video file is missing, the transparent placeholder clip bundled with the mod is copied to that path and played. |

### Video
| Option | Default | Description |
| --- | --- | --- |
| `videoPath` | `config/titlescreen/intro.webm` | Video file, relative to the game directory. |
| `videoVolume` | `100` | Audio volume, 0-100. |
| `videoOpacity` | `100` | Extra opacity multiplier on top of the video's alpha. |
| `videoFit` | `COVER` | `COVER` (crop), `CONTAIN` (letterbox) or `STRETCH`. |
| `videoMaxFps` | `60` | Upper bound for GPU texture uploads per second, `0` = unlimited. |
| `maxUploadWidth` | `1920` | Largest uploaded dimension; bigger videos are scaled down by libVLC while decoding (`0` = native). |
| `hardwareDecoding` | `false` | Decode on the GPU. Unreliable in sandboxed launchers (drops the VP9 alpha plane, a second concurrent video often delivers no frames) - enable only if software decoding cannot keep up. |
| `libVlcPath` | `""` | Optional folder containing libvlc; empty = auto-detect. |

### Timing (relative to the intro video, measured from its first displayed frame)
| Option | Default | Description |
| --- | --- | --- |
| `videoStartDelayMs` | `0` | Delay between finishing loading and the first video frame. |
| `videoFadeInMs` | `400` | Video fade-in duration. |
| `progressBarFadeStartMs` | `200` | Video timestamp at which the progress bar starts fading. |
| `progressBarFadeDurationMs` | `800` | How long the progress bar takes to disappear. |
| `overlayUnloadAtMs` | `1400` | Video timestamp at which the loading overlay (MOJANG logo) is unloaded. `0` = vanilla transition. |
| `overlayUnloadStyle` | `INSTANT` | `INSTANT` drops the loading scene at the timestamp; `VANILLA_FADE` uses vanilla's two second cross-fade underneath the video. |
| `buttonsFadeInAtMs` | `900` | Video timestamp at which the buttons start fading in. |
| `buttonsFadeInDurationMs` | `1000` | How long the buttons take to fade in. |
| `videoFadeOutMs` | `1200` | Fade used by `FADE_OUT_TO_PANORAMA`. |
| `endBehaviour` | `FADE_OUT_TO_PANORAMA` | `LOOP_REGION`, `FADE_OUT_TO_PANORAMA` or `FREEZE_LAST_FRAME`. |
| `loopStartMs` / `loopEndMs` | `0` / `0` | Loop region for `LOOP_REGION`; `loopEndMs = 0` means end of video. |

### Layout
| Option | Default | Description |
| --- | --- | --- |
| `buttonsYOffset` | `0` | Moves the buttons down (positive) or up (negative) in GUI pixels. |
| `buttonsGuiScale` | `-1` | GUI scale for the title screen buttons only; `-1` follows the vanilla GUI scale. |
| `useVanillaButtonFade` | `false` | Keep vanilla's two second fade and ignore the button timings. |

### Loading screen background
| Option | Default | Description |
| --- | --- | --- |
| `loadingBackground.enabled` | `true` | Play a video behind the vanilla loading bar instead of the plain red background. |
| `loadingBackground.videoPath` | `config/titlescreen/mojang_studios.webm` | Loading background video. |
| `loadingBackground.useBundledDefaultVideo` | `true` | Copy the bundled clip (`assets/titlescreen/video/mojang_studios.webm`) there if missing. |
| `loadingBackground.volume` | `0` | Audio volume (muted by default). |
| `loadingBackground.opacity` | `100` | Opacity multiplier. |
| `loadingBackground.fit` | `COVER` | `COVER`, `CONTAIN` or `STRETCH`. |
| `loadingBackground.fadeInMs` | `500` | Fade-in once the loading screen appears. |
| `loadingBackground.maxFps` | `30` | Upload throttle for the background video. |
| `loadingBackground.maxUploadWidth` | `1920` | Largest uploaded dimension (4K sources are scaled down by libVLC while decoding). |
| `loadingBackground.hideVanillaLogo` | `true` | Hides the vanilla MOJANG STUDIOS logo while the video is showing. |
| `loadingBackground.loop` | `false` | Off = hold the last frame, which leaves a static background for the loading bar. |
| `loadingBackground.replayOnResourceReload` | `true` | Replays the background on resource reload splashes. |
| `loadingBackground.waitForVideoToFinish` | `true` | Keeps the loading screen up until the clip has played to the end, so the next scene (intro/title) never starts mid-clip. |
| `loadingBackground.maxWaitForVideoMs` | `30000` | Safety net for the option above: continue loading after this long even if the video never ends (`0` = wait forever). |

### Video never shows up, or the game seems frozen

* **The client thread never calls into libVLC.** Native calls can block indefinitely when a player is
  in a bad state - two real freezes were traced to this (a thread dump showed the render thread stuck in
  `libvlc_media_player_stop()`). Player creation/teardown now happens on dedicated threads (teardown is
  asynchronous, so stopping a stalled player can never freeze the game), progress tracking uses uploaded
  frame counters only, and the media length comes from libVLC's `lengthChanged` event.
* **Stalled playback is detected.** libVLC occasionally stops delivering frames (the clip just freezes
  while its audio keeps playing, or it stops right at the end and never fires its end event). Both scenes
  track uploaded frames themselves:
  * the loading screen stops waiting for the background video instead of sitting there until
    `maxWaitForVideoMs` expires (`Loading background video stopped delivering frames after … frames - continuing without it`),
  * the intro video is treated as ended so it fades out / freezes like a normally finished video.
* **A missing video is recovered.** If the intro player starts but never delivers a frame it is restarted
  (up to 3 attempts, `The video intro produced no frames … restarting it`), and a failed start is retried
  instead of disabling the video for the rest of the session.
* Native libVLC start-up and teardown are serialised, so the hand-over from the loading screen (frozen
  background player) to the intro video cannot interleave inside JNA's marshalling.
* With `debugLogging: true`, a watchdog notices a frozen client (no tick for 10 s) and writes a
  **thread dump** into the log once (`The client has not ticked for … ms - writing a thread dump`). If
  the game ever hangs, that dump identifies the stuck thread - please keep it when reporting.

### Draw order

1. Loading screen: red background → **loading background video** → loading bar (the MOJANG logo is
   hidden while the video is up).
2. After loading: **intro video** above the loading overlay's logo, and once the overlay is unloaded it
   sits above the panorama but **beneath the title screen buttons** - so hover highlights, the hand
   cursor and clicks always belong to the buttons.


## How it works (performance notes)

- libVLC decodes on its own native threads; the game thread only copies the newest frame into a
  `DynamicTexture` (one upload per displayed frame, throttled by the max-FPS options) and draws it
  through the vanilla GUI render pipeline, so it composes with the new 26.2 render-state system.
- Sources bigger than `maxUploadWidth` are scaled down **by libVLC while decoding**, so even a 4K clip
  is uploaded as 1080p; the frame to GPU path is then a plain copy per row.
- The loading background is **preloaded during early startup** (first frame decoded, then paused), so it
  is already on screen the moment the loading screen appears instead of popping in after libVLC has
  spun up.
- Frames produced while the render thread is busy are dropped instead of queued.
- The intro player is created on a background thread *after* loading finished, so startup is never blocked.
- If anything goes wrong (missing file, missing libVLC, decode error) the mod disables itself and the
  vanilla loading/title screen is used unchanged.

## Building

```bash
./gradlew build
```

## Licensing note

The mod bundles [vlcj](https://github.com/caprica/vlcj) **and vlcj's `vlcj-natives` support artifact**
plus JNA via jar-in-jar so that the video feature works out of the box. vlcj-natives is required at
runtime (it contains `uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil`, used by libVLC's native
discovery) - without it the video feature fails in production builds with a `NoClassDefFoundError`.
**vlcj is GPL-3.0 licensed**, which means a build produced with `bundle_video_libraries=true` (the
default) is effectively GPL-3.0 as a whole. If you need to ship the mod under a different license, set
`bundle_video_libraries=false` in `gradle.properties`; users then have to provide vlcj themselves, or
run without video.
