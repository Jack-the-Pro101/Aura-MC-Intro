# Aura-Intro

**Turn Minecraft's start-up into a cinematic intro.** Aura-Intro plays a video behind the loading screen
and carries it straight into the title screen, with the menu fading in over it. You can use the video that
comes with the mod or your own.

> Note: This whole thing is basically vibe coded. I am experimenting with AI stuff.

---

## What it does

1. **The loading screen gets a video background.** The video is on screen from the moment the window
   opens, with the vanilla loading bar on top.
2. **It waits for the game.** The video freezes on a frame you choose until Minecraft finishes loading, so a
   slow modpack never cuts the intro short.
3. **The intro continues seamlessly.** Playback picks up from that exact frame. Nothing reloads, so there's
   no black flash or stutter.
4. **The title screen fades in on cue.** The loading bar, Mojang logo, buttons and texts each appear at
   timestamps you choose, in sync with the video.
5. **When the video ends**, it can fade back to the vanilla panorama, freeze on its last frame, or **loop a
   section** as an animated menu background. The loop can even keep playing behind the other menus.

Everything works out of the box, and everything can be tuned in-game.

## Features

- 🎬 **Bundled video**: works right after install, with nothing to download or set up
- 🎞️ **Your own video**: drop a `.webm` file into the config folder to replace it
- 🔊 **Video sound**: in sync with the picture, Bluetooth headphones included, and the menu music waits
  until the video is done
- ⏱️ **Timed fades**: buttons, splash text, version line and the "MINECRAFT" logo each fade in at their own
  moment
- 🔁 **Loop region**: keep part of the video looping as the title screen background
- 📐 **Layout options**: separate GUI scale and vertical offset for the title screen buttons
- ⚡ **GPU decoding**: NVIDIA, AMD, Intel and Apple, with automatic fallback to the CPU
- 🛡️ **Fails safe**: if anything goes wrong, you simply get the vanilla screens

## Requirements

Aura-Intro runs on **Fabric** and **NeoForge**, Minecraft **1.21 through 26.3**. Download the file for your
Minecraft version and loader; each covers a range:

| File for | Works on                                                                                   |
| -------- | ------------------------------------------------------------------------------------------ |
| 1.21.1   | 1.21, 1.21.1                                                                               |
| 1.21.3   | 1.21.2, 1.21.3                                                                             |
| 1.21.4   | 1.21.4                                                                                     |
| 1.21.5   | 1.21.5                                                                                     |
| 1.21.8   | 1.21.6, 1.21.7, 1.21.8                                                                     |
| 1.21.10  | 1.21.9, 1.21.10                                                                            |
| 1.21.11  | 1.21.11                                                                                    |
| 26.x     | 26.1, 26.2, 26.3 (one file each)                                                           |

| Loader   | Required                                                                                   | Optional                                       |
| -------- | ------------------------------------------------------------------------------------------ | ---------------------------------------------- |
| Fabric   | [Fabric API](https://modrinth.com/mod/fabric-api), [Cloth Config](https://modrinth.com/mod/cloth-config) | [Mod Menu](https://modrinth.com/mod/modmenu), to open the config screen |
| NeoForge | [Cloth Config](https://modrinth.com/mod/cloth-config)                                      | (the config screen is in NeoForge's mod list)  |

**No FFmpeg or VLC install needed.** The video decoder ships inside the mod for Windows, Linux and macOS
(x86-64 and ARM64). It also works in sandboxed launchers such as the Prism Launcher Flatpak.

## Using your own video

1. Export your video as **WebM (VP9 video + Opus audio)**. With FFmpeg:
   ```bash
   ffmpeg -i input.mov -c:v libvpx-vp9 -pix_fmt yuv420p -crf 28 -b:v 0 -deadline good -cpu-used 3 \
          -row-mt 1 -c:a libopus -b:a 128k intro.webm
   ```
2. Save it as **`config/aura-intro/intro.webm`** in your game folder.
3. Open the config screen (**Mod Menu → Aura-Intro → Config** on Fabric, **Mods → Aura-Intro → Config** on
   NeoForge) and set **Freeze frame at (ms)** to the moment where the loading screen
   should freeze, for example the end of a logo animation.
4. Adjust the fade timings to match your video. All intro timings are measured from the first frame shown on
   the title screen.

**Tip:** the frame rate and resolution matter more than the bitrate. 1080p or 1440p at 30 fps plays
smoothly almost everywhere, while 4K can be heavy for laptops, especially during loading.

## Configuration

All options are available in-game (the mod's **Config** button) and in `config/aura-intro.json`. The ones you will
most likely touch:

| Option                              | What it does                                                                 |
| ----------------------------------- | ---------------------------------------------------------------------------- |
| **Freeze frame at (ms)**            | Frame the loading screen freezes on while the game loads                     |
| **Buttons / Texts fade in at (ms)** | When the title screen buttons and texts appear                               |
| **End behaviour**                   | Fade to panorama, freeze on the last frame, or loop a region                 |
| **Loop start / Loop end (ms)**      | The section that loops as a menu background                                  |
| **Keep loop as menu background**    | Pause the loop in other menus instead of ending it (**Play loop behind other menus** keeps it running there) |
| **Volume (%)**                      | 0 for a silent intro                                                         |
| **Fit mode**                        | Cover (crop), contain (letterbox) or stretch                                 |
| **Hardware decoding**               | Decode on the GPU; turn off if playback stutters on your machine             |
| **Debug logging**                   | Write detailed playback information to the log                               |

## Performance

- The video is **decoded on its own threads**, never on the game's render thread. A heavy video can drop
  frames, but it won't freeze the game.
- Video larger than your window is **scaled down before it reaches the GPU**, so a 4K video on a 1080p screen
  costs about a quarter of the work.
- **Hardware decoding** is used where available: D3D11VA on Windows, VideoToolbox on macOS, VA-API or NVDEC on
  Linux.
- **Linux:** for GPU decoding, install your GPU's VA-API driver (`intel-media-driver` for Intel, Mesa's
  VA-API driver for AMD). The log says what's missing if it can't find one.

## Troubleshooting

**The video stutters.** Turn on **Debug logging** and look for the `Video pipeline` lines in
`logs/latest.log`. They show where the time goes. Also try turning **Hardware decoding** off, because
downloading 4K frames from some integrated GPUs is slower than decoding on the CPU. A lower-resolution video
helps everywhere.

**There's no sound.** Check **Volume (%)** under Video, and make sure your video file has an audio track.

**Something looks wrong.** Turn **Enable Aura-Intro** off to get the vanilla screens back, and please report the
issue with your `latest.log` (with debug logging on).

## Technical details

- Video playback uses **FFmpeg** (LGPL build via [JavaCPP](https://github.com/bytedeco/javacpp-presets)),
  trimmed to WebM/VP9/Opus to keep the jar small. On first launch the libraries are extracted to
  `config/aura-intro/javacpp-cache/`.
- On Linux the sound plays through PulseAudio/PipeWire like the game's own sound, so it follows your default
  output device.
- One video player handles both the loading screen and the title screen, which is why the hand-over is
  seamless.

Developers: see [`AGENTS.md`](AGENTS.md) for the architecture, the build setup (Stonecutter multi-version),
and the full config reference.

## License

Aura-Intro is licensed under the [GNU GPL v3.0](LICENSE.txt). The bundled FFmpeg libraries are licensed
under the LGPL-2.1-or-later ([ffmpeg.org](https://ffmpeg.org)).
