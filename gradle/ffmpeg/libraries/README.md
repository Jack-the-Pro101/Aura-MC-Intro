# Minimal FFmpeg libraries

This folder holds the minimal FFmpeg builds the mod bundles instead of the bytedeco ones
(VP9 + Opus decoding, VP9-only hardware acceleration - see `../build-minimal.sh` and the
"Minimal FFmpeg build" section in the repository README).

To (re)populate it:

1. Run the **Build minimal FFmpeg** workflow from the repository's Actions tab (it is
   manual-only; the libraries rarely change).
2. Download the five `ffmpeg-<version>-min-<platform>.zip` files from the finished run's
   Artifacts section. The artifacts hold the libraries loose; GitHub packs each download
   into that zip itself, so it is ready to commit - no zip inside the zip to extract.
3. Drop the zips into this folder and commit them.

The Gradle build picks the zips up through the `ffmpeg_libraries` property in
`gradle.properties`; platforms without a zip fall back to the trimmed bytedeco libraries with a
warning. Keep the file names unchanged - they are matched on the `-<platform>.zip` suffix.
