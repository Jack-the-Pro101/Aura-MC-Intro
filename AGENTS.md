# AGENTS.md

Notes for coding agents (and humans) working on **Aura-Intro**: how the project is laid out, how to
build and test it, and the design decisions behind the video pipeline. The user-facing description
lives in `README.md` - keep that one short and readable, and put technical detail here.

## What the mod does

A client-side mod for **Fabric and NeoForge**, Minecraft **1.21 - 26.3**, that replaces the
vanilla loading/title screen presentation with a configurable **video intro** - typically one baked
clip that covers the whole start-up:

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
- the buttons get their own **vertical offset** and the title screen its own **GUI scale**, independent of the
  rest of the UI,
- when the video ends it can **loop a region**, **fade back to the vanilla panorama** or **freeze** on the last
  frame; a loop can persist as the title screen background and optionally keep playing behind the other menus,
- the vanilla "MINECRAFT" wordmark can stay hidden while the video plays and **starts fading in the moment the
  video ends** (`fadeInAfterVideo`, on by default), so it cross-dissolves with the video's fade-out.

Everything is configurable in-game through a Cloth Config screen (Cloth Config is an external dependency):
**Mod Menu → Config** on Fabric (Mod Menu optional), NeoForge's own mod list **Config** button on NeoForge.

## Supported versions (build nodes)

One jar per (Minecraft version, loader). A node compiles against one Minecraft version; its jar declares the
range it runs on:

| Node MC  | Jar runs on      | NeoForge         | Notes                                                       |
| -------- | ---------------- | ---------------- | ----------------------------------------------------------- |
| 1.21.1   | 1.21 - 1.21.1    | 21.1 (1.21.1)    | Immediate-mode GUI, `setColor`, GL texture uploads          |
| 1.21.3   | 1.21.2 - 1.21.3  | 21.2 - 21.3      | Batched GUI by `RenderType`                                 |
| 1.21.4   | 1.21.4           | 21.4             |                                                             |
| 1.21.5   | 1.21.5           | 21.5             | `GpuTexture` + command encoder (`IntBuffer` uploads)        |
| 1.21.8   | 1.21.6 - 1.21.8  | 21.6 - 21.8      | GUI render state, `RenderPipeline`, `Matrix3x2fStack`       |
| 1.21.10  | 1.21.9 - 1.21.10 | 21.9 - 21.10     | `LoadingOverlay.tick()`, `renderWithTooltipAndSubtitles`    |
| 1.21.11  | 1.21.11          | 21.11            | `Identifier`, textures carry a `GpuSampler`                 |
| 26.1     | 26.1.x           | 26.1 (26.1.2)    | Unobfuscated, `GuiGraphicsExtractor`                        |
| 26.2     | 26.2             | 26.2             |                                                             |
| 26.3     | 26.3             | 26.3 (beta)      | renderpearl API packages                                    |

The grouped ranges (1.21.2/1.21.3, 1.21.6-1.21.8, 1.21.9/1.21.10) are hotfix-compatible releases; only the
node's own Minecraft version is exercised by dev runs.

## Repository layout

| Path                                                 | What it is                                                                                     |
| ---------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `src/client/java/.../client/AuraIntroCommon.java`    | Loader-independent start-up (config, `ALSOFT_DRIVERS`, preload), tick and shutdown hooks       |
| `src/client/java/.../client/AuraIntroClient.java`    | Fabric client entrypoint (`//? if fabric`)                                                     |
| `src/client/java/.../client/AuraIntroPreLaunch.java` | Fabric pre-launch entrypoint: loads FFmpeg before the window exists                            |
| `src/client/java/.../client/AuraIntroModMenu.java`   | Fabric Mod Menu integration                                                                    |
| `src/client/java/.../client/AuraIntroNeoForge.java`  | NeoForge entrypoint (`//? if neoforge`): FFmpeg warm-up, events, config screen extension point |
| `src/client/java/.../client/config/`                 | `AuraIntroConfig` (the config model, Cloth Config annotations) and its holder                  |
| `src/client/java/.../client/video/AuraIntroVideoManager.java` | The intro state machine: scenes, timings, hand-over, loops, watchdogs                 |
| `src/client/java/.../client/video/FfmpegVideoPlayer.java`     | FFmpeg player: demux, decode (software/hardware), RGBA conversion, A/V clock          |
| `src/client/java/.../client/video/VideoFrameSink.java`        | Triple-buffered hand-off from the decode thread to the render thread                  |
| `src/client/java/.../client/video/VideoTextureLayer.java`     | GPU texture per scene and how it is drawn (fit modes, sampler)                         |
| `src/client/java/.../client/video/*Output.java`               | Audio outputs: libpulse (`PulseAudioOutput`) and Java Sound fallback                   |
| `src/client/java/.../client/compat/McCompat.java`    | Small per-version API shims (screen/overlay access, pose stack, config screen)                 |
| `src/client/java/.../client/compat/Platform.java`    | The loader-specific calls (config and game directory)                                          |
| `src/client/java/.../mixin/client/`                  | Mixins into the loading overlay, title screen, widgets, music manager, ...                     |
| `src/main/resources/assets/aura-intro/video/`        | The bundled default video (`default_intro.webm`, 3840x2160 VP9 + Opus, 25 fps)                 |
| `src/main/resources/fabric.mod.json`, `META-INF/neoforge.mods.toml` | Mod metadata; each loader's jar only gets its own |
| `gradle/mc/<mc>.properties`                          | Per-Minecraft-version values for both loaders: dependency versions, ranges, Java target       |
| `settings.gradle`                                    | The node matrix (`<mc>-<loader>`) and which build script each node uses                        |
| `build.gradle` / `build.remap.gradle` / `build.neoforge.gradle` | Node build scripts: Fabric 26.x, Fabric 1.21.x (remapping), NeoForge         |
| `gradle/common.gradle`                               | Shared build logic: properties, Stonecutter constants and name swaps, FFmpeg natives trimming  |
| `gradle/fabric.gradle`, `gradle/neoforge.gradle`     | Loader-specific dependencies, jar-in-jar, runs                                                 |
| `gradle/ffmpeg/`                                     | Minimal FFmpeg build script and the committed per-platform library zips                        |
| `sources/` (not committed)                           | Local FFmpeg source checkout, handy for reading `libswscale`/`hwcontext_vaapi.c`                |

## Conventions

- **Stonecutter**: one shared source tree, built once per node. Version differences are written as Stonecutter
  comment conditions (`//? if >=26.1 { ... //?} else { /* ... *///?}`), loader differences the same way with the
  `fabric` / `neoforge` constants (`//? if neoforge {`), and a few name swaps live in `gradle/common.gradle`.
  The active node (the one `src/` reflects in the IDE) is set in `stonecutter.gradle` (`26.2-fabric`); code for
  other nodes sits in `src/` commented out, with nested block comments escaped as `/^ ... ^/` - so never put
  `/^` inside a string in such a block (that is why the NeoForge lambda selector has no `^` anchor).
- **Prefer one branch per GUI generation** over clever sharing: the mixin targets (`blit`, `blitSprite`,
  `innerBlit`, `drawString`, splash) changed signature at 1.21.2, 1.21.6 and 26.1, and each generation gets its
  own handler. Mixin target strings are not checked by the compiler - only a game run validates them, and a
  missing target crashes at class load (`defaultRequire: 1`).
- **NeoForge patches some vanilla code** the mixins hook: `TitleScreen` draws its branding lines (the version line
  among them) through `BrandingControl` lambdas; before 1.21.6 those lambdas are hooked by regex selector.
  Regex selectors only work on NeoForge - on Fabric 1.21.x the names are remapped and regexes are not.
- **Comments explain why**, usually with the concrete failure that motivated the code. Match the existing
  density and tone; the codebase is heavily commented on purpose.
- **Threads**: the client/render thread never calls into FFmpeg. Decoding, conversion and audio run on the
  player's own threads; player creation and teardown happen on background threads. Keep it that way - a
  native call on the render thread can freeze the game.
- **Fail safe**: if anything goes wrong (missing file, FFmpeg not loadable, decode error) the mod disables
  itself and the vanilla screens are used unchanged. New features must keep that property.
- The mod has a `debugLogging` option; new diagnostics belong behind it (one-shot or rate-limited lines,
  never per frame).

## Building

```bash
./gradlew :1.21.1-neoforge:build            # one node
./gradlew build                             # every node (20 jars)
./gradlew compileClientJava                 # quick compile check of every node
```

Each build lands in `versions/<node>/build/libs/` as `Aura-Intro-<mod version>+<mc>-<loader>.jar`. Fabric 1.21.x
builds are remapped to intermediary names (`fabric-loom-remap`; loom rewrites the mixin target strings in place,
there is no refmap), Fabric 26.x builds compile against the unobfuscated jars, and NeoForge builds use
ModDevGradle on Mojang's names throughout.

ModDevGradle needs a **Java 21 toolchain** for its tools (and 1.21.x NeoForge runs on it). Gradle finds
installed JDKs on its own; where it cannot (NixOS), point it at one:
`-Porg.gradle.java.installations.paths=/path/to/jdk21`.

The Modrinth and local FFmpeg repositories are declared with `exclusiveContent`: before that, one 502 from
NeoForge's maven while it was (pointlessly) asked for a Modrinth artifact disabled that repository for the
whole build.

### Running it

`./gradlew :<node>:runClient` (e.g. `:1.21.1-neoforge:runClient`) starts a dev client with its run directory at
`versions/<node>/run/` (gitignored; its `config/aura-intro.json` is the dev config - turn `debugLogging` on
there). On NixOS the FFmpeg natives need system libraries on `LD_LIBRARY_PATH` (the Prism Launcher wrapper's
value works). Logs go to `versions/<node>/run/logs/latest.log`. Reaching the title screen with the video playing
validates every mixin of that node.

NeoForge dev-run quirks:

- NeoForge 21.1-21.8 (FML with ModLauncher) does not show plain classpath libraries to mods in dev runs, so
  `gradle/neoforge.gradle` also puts FFmpeg on `additionalRuntimeClasspath` for those versions. Built jars nest
  it with jar-in-jar instead (one artifact per platform: jar-in-jar tells nested jars apart by group and
  artifact only).
- NeoForge 21.9+ shows a "warnings while loading mods" screen because Cloth Config uses `@OnlyIn`; click
  through it.
- NeoForge's early loading window needs an OpenGL visual; where there is none (the 26.3 test machine),
  `earlyWindowControl = false` in the run's `config/fml.toml` gets past it.

Useful debug log lines when checking playback:

- `Video pipeline (...)`: every ~5 s, where the decode thread's time per frame goes (decode, GPU download,
  RGBA conversion, waiting for the frame's time) plus late/skipped frame counts. The first thing to ask
  for when someone reports a laggy video.
- `Loading video is playing at N fps` / `Video intro is playing at N fps`: what actually reached the screen.
- `A/V sync: ...`: picture position against the audible sound, every ~5 s.
- `Heartbeat: ...`: every 15 s while a video is on screen, so a stalled scene is visible in the log.
- `No usable hardware video decoder ...`: always logged once, with what each GPU device said and a hint.

## Video format

Recommended: **WebM with 8-bit VP9 video and an Opus audio track**:

```bash
ffmpeg -i input.mov -c:v libvpx-vp9 -pix_fmt yuv420p -crf 28 -b:v 0 -deadline good -cpu-used 3 \
       -row-mt 1 -c:a libopus -b:a 128k intro.webm
```

8-bit 4:2:0 is much cheaper than 10-bit or 4:2:2: every frame is converted to 8-bit RGBA on the CPU. The
minimal FFmpeg build only contains the Matroska demuxer and the VP9/Opus decoders; with the full bytedeco
libraries anything FFmpeg decodes would work.

The bundled video is 4K. On weaker machines (or Intel iGPUs, where downloading hardware-decoded 4K frames
is expensive) a 1440p/1080p clip cuts decode, download and conversion work by 2-4x.

### Single baked video (loading screen + intro in one file)

Both scenes come from **one** video (the Mojang/loading part first, then the intro), set by `videoPath`.
The loading screen plays from 0 to `loadingBackground.holdAtMs`, **holds that exact frame** while the game
finishes loading, and the intro then **continues from that same frame** - no gap, no second file, no
transparency tricks. All `timing` values stay relative to the first frame visible on the title screen, and
the video's look (`videoOpacity` / `videoFit` / `videoMaxFps`) applies to both scenes; the loading scene's own
fade-in is `loadingBackground.fadeInMs`.

The video file defaults to `config/aura-intro/intro.webm`. Without a file there, the video bundled in the jar
plays (`useBundledDefaultVideo`); it is unpacked to `config/aura-intro/bundled/` because the decoder opens
files by real path.

## Configuration reference

The config lives in `config/aura-intro.json` (model: `AuraIntroConfig`). **Every timing is relative to the
scene it belongs to**:

| Scene                                | Reference point for its timings                                                                    |
| ------------------------------------ | -------------------------------------------------------------------------------------------------- |
| Loading screen (`loadingBackground`) | the moment the loading screen appears and the video starts (`fadeInMs`, `maxWaitForVideoMs`)       |
| Intro video (`timing`)               | the **first frame that is actually on screen** on the title screen (everything in `timing`)        |

The intro timeline deliberately starts with the first displayed frame rather than when the decoder is asked to
start: starting a video takes a moment, and using the request time shifted every timing by that amount (most
visibly the button fade-in). If the video never produces a frame, the provisional timeline from the anchor is
used after a few seconds so the loading screen still clears.

### General

| Option                   | Default | Description                                                                                                                                       |
| ------------------------ | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `enabled`                | `true`  | Master switch - disables every behaviour of the mod when off.                                                                                     |
| `fadeInAfterVideo`       | `true`  | Keep the "MINECRAFT" wordmark hidden while the video plays and fade it in when the video ends. Off leaves it exactly as vanilla draws it.         |
| `wordmarkAfterFirstLoop` | `false` | With a loop region (which never ends): fade the wordmark in the first time the video loops. Off keeps it hidden while the loop plays.             |
| `hideSplashText`         | `false` | Hides the rotating yellow splash text.                                                                                                            |
| `debugLogging`           | `false` | Logs the intro state machine and playback diagnostics (see "Running it").                                                                         |
| `suppressMenuMusic`      | `true`  | Keep the menu music quiet while the video plays, so it cannot talk over the video's soundtrack. The music returns when the video ends (or loops). |
| `replayOnResourceReload` | `false` | Replays the whole intro (loading screen included) on resource reloads (F3+T, resource pack changes).                                              |
| `useBundledDefaultVideo` | `true`  | Play the video bundled inside the mod when the configured file is missing.                                                                        |

### Video

| Option              | Default                        | Description                                                                                                                                                                                                                                        |
| ------------------- | ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `videoPath`         | `config/aura-intro/intro.webm` | The video file for both scenes; relative to the game directory (or absolute).                                                                                                                                                                      |
| `videoVolume`       | `100`                          | Audio volume, 0-100.                                                                                                                                                                                                                               |
| `audioInSamePlayer` | `true`                         | Play the sound inside the video player: one clock, so no drift and no delay needed, and the decoder keeps the sound on time by dropping late video frames. Off = a separate audio-only player, which a slow video pipeline cannot starve.          |
| `audioDelayMs`      | `0`                            | Shifts the sound relative to the picture (positive = later). Only used when `audioInSamePlayer` is off.                                                                                                                                            |
| `audioLatencyMs`    | `0`                            | Fine-tuning on top of the output latency the mod measures by itself (on Linux through the sound server, Bluetooth included). Positive delays the picture further. Usually `0`.                                                                     |
| `videoAudioDevice`  | *(empty - automatic)*          | Output device for the video's sound. Empty follows the system default output. On Linux a sink name as shown by the desktop's audio widget; only when the sound server is unreachable a Java Sound mixer name instead (the log lists them).          |
| `videoOpacity`      | `100`                          | Extra opacity multiplier on top of the video's alpha, in both scenes.                                                                                                                                                                              |
| `videoFit`          | `COVER`                        | `COVER` (crop), `CONTAIN` (letterbox) or `STRETCH`, in both scenes.                                                                                                                                                                                |
| `videoMaxFps`       | `60`                           | Upper bound for GPU texture uploads per second (both scenes), `0` = unlimited. Does not reduce decode cost.                                                                                                                                       |
| `hardwareDecoding`  | `true`                         | Decode on the GPU: D3D11VA on Windows, VideoToolbox on macOS, VAAPI (Intel/AMD) or NVDEC (NVIDIA) on Linux. Falls back to software decoding when no working decoder is found.                                                                     |

### Timing (relative to the intro video's first displayed frame)

| Option                      | Default                | Description                                                                                                                                       |
| --------------------------- | ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `videoFadeInMs`             | `0`                    | Video fade-in. Only used when the loading screen does not show the video (otherwise it is already on screen and just keeps playing).            |
| `progressBarFadeStartMs`    | `200`                  | When the progress bar starts fading.                                                                                                              |
| `progressBarFadeDurationMs` | `800`                  | How long the progress bar takes to disappear.                                                                                                     |
| `overlayUnloadAtMs`         | `1400`                 | When the loading overlay (MOJANG logo) is unloaded. `0` = vanilla transition.                                                                     |
| `overlayUnloadStyle`        | `INSTANT`              | `INSTANT` drops the loading scene at the timestamp; `VANILLA_FADE` uses vanilla's two second cross-fade underneath the video.                     |
| `buttonsFadeInAtMs`         | `2150`                 | When the buttons start fading in.                                                                                                                 |
| `buttonsFadeInDurationMs`   | `1000`                 | How long the buttons take to fade in.                                                                                                             |
| `textFadeInAtMs`            | `2800`                 | When the version/mod-count line and the splash text start fading in. The Realms badge fades in from the moment it appears instead.               |
| `textFadeInDurationMs`      | `1000`                 | How long those texts take to fade in (also the Realms badge's and the wordmark's fade).                                                           |
| `videoFadeOutMs`            | `1200`                 | Fade used by `FADE_OUT_TO_PANORAMA`.                                                                                                              |
| `endBehaviour`              | `FADE_OUT_TO_PANORAMA` | `LOOP_REGION`, `FADE_OUT_TO_PANORAMA` or `FREEZE_LAST_FRAME`.                                                                                     |
| `loopStartMs` / `loopEndMs` | `7480` / `16280`       | Loop region for `LOOP_REGION`; `loopEndMs = 0` means end of video. After the first wrap the loop is muted and the menu music takes over.          |
| `persistLoopAsBackground`   | `false`                | `LOOP_REGION` only: leaving the title screen pauses the loop instead of ending it; it resumes when the title screen is back.                     |
| `loopInOtherMenus`          | `false`                | With `persistLoopAsBackground`: the loop keeps playing (blurred) behind the other menus where vanilla shows its panorama. In a world it pauses. |

### Layout

| Option                 | Default | Description                                                                                                                                                                     |
| ---------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `buttonsYOffset`       | `0`     | Moves the buttons down (positive) or up (negative) in GUI pixels.                                                                                                               |
| `buttonsGuiScale`      | `0`     | GUI scale for the whole title screen, with vanilla's semantics: `0` = Auto, `1`-`4` fixed (clamped to what the window supports); `-1` follows the game's GUI scale.            |
| `useVanillaButtonFade` | `false` | Keep vanilla's two second button fade and ignore the button timings.                                                                                                            |

### Loading screen (`loadingBackground`)

| Option                   | Default | Description                                                                                                                                                         |
| ------------------------ | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `enabled`                | `true`  | Draw the video behind the vanilla loading bar. Off keeps the loading screen vanilla; the video then starts (and fades in via `videoFadeInMs`) once loading is done. |
| `fadeInMs`               | `0`     | Fade-in once the loading screen appears; `0` cuts straight to the first frame.                                                                                      |
| `hideVanillaLogo`        | `true`  | Hides the MOJANG STUDIOS logo while the video is showing.                                                                                                           |
| `holdAtMs`               | `3400`  | Frame at which the loading scene freezes and waits for the game; the intro continues from exactly that frame. `0` plays the clip through during loading.           |
| `waitUntilHoldMsReached` | `true`  | Keep the loading screen up until the video reached `holdAtMs` (or its end), so the intro never starts from a frame the loading scene has not shown. Formerly `waitForVideoToFinish`, which is still read. |
| `maxWaitForVideoMs`      | `30000` | Safety net for the option above (`0` = wait forever).                                                                                                               |

## Architecture and design notes

### Video pipeline

- **Decode**: FFmpeg (via the JavaCPP preset) decodes on the player's decode thread, VP9 frame-threaded
  across every core for software decoding. With `hardwareDecoding` the decoding runs on the GPU (VAAPI render
  nodes are tried one by one, because FFmpeg's default only tries the first - often the wrong GPU on hybrid
  laptops) and each frame is downloaded to system memory once.
- **Convert**: `swscale` converts to RGBA directly into one of the sink's staging buffers - no intermediate
  copy. A source larger than the window is scaled to window size in the same pass (the render thread reports
  the on-screen size via `VideoFrameSink.noteOutputSize`), so a 4K clip on a 1080p screen converts, copies and
  uploads a quarter of the pixels. The conversion goes through `sws_scale_frame` with slice threads (plain
  `sws_scale` is single-threaded even on a threaded context); measured on the bundled clip, 4K→1080p went from
  ~3.5 ms to ~0.8 ms per frame. Its destination must be a refcounted frame, so each staging buffer is wrapped
  in an `AVBufferRef` whose free callback does nothing - the sink owns that memory.
- **Hand-off**: `VideoFrameSink` keeps three buffers - one published, one being written, one possibly being
  uploaded - so neither side waits for the other and no frame is copied. Frames produced while the render
  thread is busy are dropped instead of queued.
- **Alpha**: for opaque scenes the alpha channel is forced on the decode thread; after five frames without any
  transparency the pass is dropped entirely (at 4K it cost more than everything else per frame).
- **Upload/draw**: the render thread hands the newest buffer straight to the GPU (`VideoTextureUploader`: the
  device's command encoder from 1.21.5 on, a plain `glTexSubImage2D` before), skipping Minecraft's
  `NativeImage` copy, and draws it with the GUI's own `innerBlit` (`GuiGraphicsInvoker` /
  `GuiGraphicsExtractorInvoker`, one signature per GUI generation). The video is filtered **linearly** instead of
  the nearest-neighbour sampling Minecraft gives dynamic textures (point sampling made the picture blocky):
  `setFilter` up to 1.21.10, the texture's own `GpuSampler` on 1.21.11, a per-draw sampler on 26.x.
- **Immediate-mode quirks (before 1.21.6)**: 1.21.1 draws the video right away, so the loading overlay's
  additive blend mode is reset for it and restored for the logo; 1.21.2-1.21.5 batch GUI draws, so the menu
  background loop is flushed before vanilla's blur post-processes the framebuffer.
- **Thread priority**: the decode thread runs at normal priority. Demoting it below normal once starved the
  whole picture pipeline on Windows, one of the platforms that actually enforces Java thread priorities.
- **Preload**: the first frame is decoded during early start-up (Fabric's `AuraIntroPreLaunch`, NeoForge's mod
  constructor - NeoForge has no pre-launch hook, but it runs during NeoForge's own early loading screen, still
  well before the vanilla one) and the decoder parks
  itself on it, so the video is on screen the moment the loading screen appears and playback continues from
  that frame - no seek, no decoder flush. The preload is silent; volume is only applied once it is visible.
- **One player for both scenes**: with a baked video there is exactly one video player and one texture, so the
  hand-over from loading screen to title screen allocates, opens and decodes nothing.

### Why not zero-copy GPU playback

With hardware decoding the frame still makes one trip GPU → system memory → GPU. Truly GPU-resident playback
(decoding straight into a texture the game renders from) is not reachable from this architecture: FFmpeg's
hwaccel output is a VAAPI surface, CUDA array, D3D11 texture or VideoToolbox pixel buffer, and importing those
into Minecraft's OpenGL/Vulkan context needs per-platform interop (EGL dma-buf import, `WGL_NV_dx_interop2`,
IOSurface textures) that neither FFmpeg nor its Java bindings expose portably. Minecraft's Windows GL context
cannot import D3D11 textures outside vendor-specific extensions, and macOS' IOSurface path needs unnormalized
rectangle-texture sampling the GUI pipeline does not offer. mpv/VLC do zero-copy because they own their GL
context. The download is known to be expensive on Intel iGPUs at 4K (`av_hwframe_transfer_data` goes through
`vaCreateImage`/`vaGetImage` plus a copy per frame - see `libavutil/hwcontext_vaapi.c`).

### Audio and A/V sync

- On Linux the sound plays through the system sound server (PulseAudio or PipeWire's pulse layer) via
  libpulse - the same route OpenAL takes - so it follows the desktop's default output and works inside
  Flatpak/Snap. Where libpulse is missing, Java Sound takes over, preferring sound-server-backed devices over
  raw hardware. The design decision to keep this separate player (rather than routing through Minecraft's
  sound engine) stands until Windows/macOS can be tested.
- The mod sets `ALSOFT_DRIVERS=pulse,alsa` inside the game process on Linux (never overriding an existing
  value) so the game's own OpenAL also comes up in sandboxed launchers.
- Inside one player the picture is paced against a single playback clock whose master is wall time: pauses
  freeze it, seeks re-anchor it. An output whose device position can be fully accounted for (Java Sound's
  `SourceDataLine`) drives the clock instead. Through the sound server each chunk is written when the wall
  clock reaches its timestamp, the stream keeps a fixed ~200 ms buffer, and the latency the server reports
  (buffer plus sink delay, e.g. a Bluetooth codec) is what the picture is delayed by.
- The demuxer keeps ~400 ms of decoded audio ahead of the clock before its pacing may stall; while refilling
  it, far-ahead video frames are decoded but not converted or shown.
- The audio output is opened during the silent preload, not when the loading scene starts (opening late cost
  about a second of silence and delay). While paused, the stream keeps playing silence so the sound server
  never suspends the sink - waking a Bluetooth sink takes a second or two.
- With `audioInSamePlayer` off, picture and sound are separate players: their positions are equalised while
  both are paused (at the freeze frame both seek onto `holdAtMs`), and while playing the sound is re-seeked
  onto the picture if it drifts more than ~250 ms behind or ~750 ms ahead for longer than a hiccup. That
  closed the failure mode where a stall during a heavy modpack's first launch left the sound lagging for good.

### Robustness

- The client thread never calls into the decoder; it only reads cached values. Teardown joins the player's
  threads with a timeout, and a thread that refuses to stop leaks its context rather than freezing the game.
- Stalled playback is detected from produced frames: the loading screen stops waiting for a video that stopped
  delivering frames, and the intro is treated as ended so it fades out/freezes normally.
- An intro player that starts but never delivers a frame is restarted (up to 3 attempts); a failed start is
  retried instead of disabling the video for the session.
- Player start-up and teardown are serialised, so the loading-to-intro hand-over cannot interleave inside the
  FFmpeg contexts.
- With `debugLogging`, a watchdog notices a frozen client (no tick for 10 s) and writes a **thread dump** into
  the log once.

### Draw order

1. Loading screen: red background → **loading background video** → loading bar (the MOJANG logo is hidden
   while the video is up).
2. After loading: the **intro video** above the loading overlay's logo; once the overlay is unloaded it sits
   above the panorama but **beneath the title screen buttons**, so hover highlights, the cursor and clicks
   belong to the buttons.
3. With `loopInOtherMenus`, the loop is drawn where other menus draw their panorama, before the blur.

The title screen is rendered underneath the loading overlay, so it draws the same video layer while the overlay
is still up. That covers the panorama and buttons during the overlay's fade-out, and is why nothing of the
title screen can flash between the overlay disappearing and the intro taking over. Only the loading overlay the
intro took over gets this treatment (`AuraIntroVideoManager.isIntroOverlay`); a later resource reload's overlay
stays vanilla, loading bar included.

## FFmpeg libraries and jar size

The mod bundles FFmpeg (`avutil`, `avcodec`, `avformat`, `swresample`, `swscale` plus the JavaCPP JNI wrappers)
for Windows x86-64, Linux x86-64/ARM64 and macOS x86-64/ARM64 via jar-in-jar. On first use they are extracted
to `config/aura-intro/javacpp-cache/` and reused on every launch. No system FFmpeg or VLC is needed.

The build trims each platform's natives jar to the libraries the player loads (verified against each library's
`NEEDED` entries: `libavcodec` hard-links `libva`/`libavutil`/`libswresample`; `libavformat` links
`libavcodec`). `avdevice`, `avfilter`, the command-line programs and GraalVM metadata are stripped.

### Minimal FFmpeg build (GitHub Actions)

`.github/workflows/ffmpeg.yml` builds a minimal FFmpeg (`--disable-everything`; only the Matroska demuxer,
VP9/Opus decoders and parsers, the file protocol, swscale and swresample - no encoders, no network) **with VP9
hardware decoding** (VAAPI on Linux plus NVDEC on x86-64, D3D11VA/DXVA2 on Windows, VideoToolbox on macOS) for
all five platforms. It is manual-only (`workflow_dispatch`); the libraries rarely change.

Because it is the **same FFmpeg release** the JavaCPP preset targets, the public ABI is identical and
bytedeco's JNI wrappers work unchanged. The zips are ~5 MB per platform instead of ~23 MB; the mod jar is
~29 MB, of which ~13 MB is the bundled video.

**Updating them:** run the workflow, download the five zips from the run's Artifacts and commit them to
`gradle/ffmpeg/libraries/` (the default of the `ffmpeg_libraries` property):

```
gradle/ffmpeg/libraries/
  ffmpeg-8.1.2-min-linux-arm64.zip
  ffmpeg-8.1.2-min-linux-x86_64.zip
  ffmpeg-8.1.2-min-macosx-arm64.zip
  ffmpeg-8.1.2-min-macosx-x86_64.zip
  ffmpeg-8.1.2-min-windows-x86_64.zip
```

Platforms without a zip fall back to the trimmed bytedeco build with a warning. `-Pffmpeg_libraries=<folder>`
points the build elsewhere (accepts `ffmpeg-*-min-<platform>.zip`, `<platform>.zip` or `<platform>/`
subfolders). `gradle/ffmpeg/build-minimal.sh` is the script behind the workflow and also runs locally
(`bash gradle/ffmpeg/build-minimal.sh linux-x86_64`). `bundle_video_libraries=false` builds a jar without
the bundled libraries.

## Licensing

The mod is **GPL-3.0-only** (`LICENSE.txt`, `fabric.mod.json`); the license text is packed into every jar as
`LICENSE_Aura-Intro.txt`. FFmpeg is LGPL-2.1-or-later (source: https://ffmpeg.org); the bundled builds are LGPL
builds. JavaCPP and its presets are Apache-2.0/GPL-2.0-with-classpath-exception; JNA is dual
Apache-2.0/LGPL-2.1 - all compatible with GPL-3.0.
