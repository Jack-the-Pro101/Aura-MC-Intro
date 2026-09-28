# Titlescreen

A Fabric mod for Minecraft **26.2** that replaces the vanilla loading/title screen presentation with a
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


* **Resolution**: frames are kept at the video's native size and handed to libVLC with our own buffer
  format, so libVLC's scaler (which crashed natively more than once) is never involved. The cost per frame
  is one copy into the texture plus the GPU upload - the alpha handling for the opaque scenes happens on
  libVLC's own thread. If a 4K clip is too much for the machine anyway, `videoMaxFps` /
  `loadingBackground.maxFps` bound how often a frame is uploaded, and `hardwareDecoding` keeps the decode
  on the GPU.
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
| `audioDelayMs` | `0` | Shifts a baked video's sound relative to its picture in milliseconds. Positive plays the sound later - the usual fix, because the picture arrives a few frames late at 4K while the sound does not. Tune by ear; a faster machine needs less, or a negative value. |
| `audioInSamePlayer` | `false` | Play the sound inside the video player instead: one clock, so no drift and no delay needed. The sound then shares the video's demux, so it can stutter while the game loads a resource pack. |
| `videoOpacity` | `100` | Extra opacity multiplier on top of the video's alpha. |
| `videoFit` | `COVER` | `COVER` (crop), `CONTAIN` (letterbox) or `STRETCH`. |
| `videoMaxFps` | `60` | Upper bound for GPU texture uploads per second, `0` = unlimited. |
| `hardwareDecoding` | `true` | Decode on the GPU. Leave on: software decoding of 4K cannot keep up (the video stutters and ends after a handful of frames). libVLC falls back to software itself if the driver cannot handle the stream. |
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
| `textFadeInAtMs` | `900` | Video timestamp at which the version/mod-count line and the splash text start fading in (fully transparent before it). The Realms badge fades in from the moment it appears instead. |
| `textFadeInDurationMs` | `1000` | How long those texts take to fade in (also the Realms badge's fade). |
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
| `loadingBackground.hideVanillaLogo` | `true` | Hides the vanilla MOJANG STUDIOS logo while the video is showing. |
| `loadingBackground.useIntroVideo` | `true` | Use the intro video for the loading screen too - one file with the loading part first and the intro after it. One player, one decoder, no hand-over. Off = use the separate file above. |
| `loadingBackground.holdAtMs` | `0` | Frame of that video at which the loading scene freezes and waits for the game to finish loading; the intro continues from exactly that frame. `0` plays the whole clip during loading. |
| `loadingBackground.loop` | `false` | Off = hold the last frame, which leaves a static background for the loading bar. |
| `loadingBackground.replayOnResourceReload` | `true` | Replays the background on resource reload splashes. |
| `loadingBackground.waitForVideoToFinish` | `true` | Keeps the loading screen up until the clip has played to the end, so the next scene (intro/title) never starts mid-clip. |
| `loadingBackground.maxWaitForVideoMs` | `30000` | Safety net for the option above: continue loading after this long even if the video never ends (`0` = wait forever). |

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
2. **`video.hardwareDecoding: true`** (the default) keeps the decode itself off the CPU where the driver
   supports the codec.
3. A lower `videoMaxFps` / `loadingBackground.maxFps` does *not* help here - the cost is per decoded frame,
   not per upload.

The picture and the sound are separate players with their own clocks, so their positions are equalised while
both are paused: at the freeze frame both are seeked onto exactly `holdAtMs`, and when the loading scene
starts the sound is seeked to the same position as the picture. Doing it while paused means it cannot be
heard, and afterwards both run in real time from the same frame. The remaining difference is the audio
output's own re-fill after such a seek - tens of milliseconds, and constant rather than growing. If a
constant offset remains, `video.audioDelayMs` shifts the sound by that many milliseconds (positive plays it
later); the picture's pipeline is the slow one at 4K, so a positive value is the usual fix.

With `debugLogging: true` a heartbeat is logged every 5 s while a video is on screen (frame counters, alpha,
positions, scene flags). It exists so that a stalled scene is visible in the log: a freeze that leaves no
other trace is otherwise impossible to tell apart from a log that simply ended.

Note that the sound can start a hair after the picture, but no longer by a noticeable margin: the audio
player's preload is silenced with a **startup volume** (`--volume=0`) instead of by switching its audio track
off. Switching the track off stops libVLC from ever creating its audio output, and creating that output when
the loading scene begins costs about a second of silence *and* the same second of delay - libVLC then plays
the samples it queued from the start, so the sound runs behind the picture. A player that starts silently at
volume 0 has a live audio output from the first samples: resuming it only means turning the volume up.

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

The title screen is rendered *underneath* the loading overlay, so it draws the same video layer while the
overlay is still up. That is what covers the panorama and the buttons during the overlay's fade-out - and
it is also why nothing of the title screen can flash between the overlay disappearing and the intro
taking over.


## How it works (performance notes)

- libVLC decodes on its own native threads; the game thread copies the newest decoded frame straight into
  the texture's pixels (one copy, one upload per displayed frame, throttled by the max-FPS options) and
  draws it through the vanilla GUI render pipeline, so it composes with the new 26.2 render-state system.
- Frames are kept at the video's native size and handed to libVLC with our own buffer format: no scaler
  inside libVLC (that crashed natively more than once) and no resampling pass in Java.
- The video is drawn with a **linear, clamp-to-edge** sampler instead of the nearest/repeat one Minecraft
  creates dynamic textures with, so scaling the clip to the screen is filtered rather than point-sampled
  (that point sampling is what made the picture look blocky and jagged next to a video player's scaler).
- Everything that can be done off the render thread is: an opaque scene's alpha is dropped while libVLC's
  thread writes the frame, which is where the spare time is. Doing it per pixel on the render thread cost
  more than the rest of the frame combined at 4K, and that was what made the loading video stutter.
- The loading scene's video is **preloaded during early startup** (libVLC loaded before the window exists,
  first frame decoded and then paused), so it is already on screen the moment the loading screen appears.
- With a baked single video there is exactly one *video* player and one texture for both scenes, so the
  hand-over allocates, opens and decodes nothing. Its **sound** is played by a separate audio-only player:
  one player has one demux and decoder path for both streams, so a 4K video that falls behind while the
  game loads a resource pack delays the audio packets with it - which is heard as the sound cutting out.
  Two players, two sets of threads: the picture may drop frames, the sound does not. (Two *video* players on
  the same 4K file is still the thing that crashed natively, so the picture stays in one player.)
- Frames produced while the render thread is busy are dropped instead of queued.
- Nothing is started on the render thread: player creation, teardown and all libVLC control calls happen on
  background threads (see `VlcVideoPlayer`).
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
