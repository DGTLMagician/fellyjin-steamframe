# Fellyjin for Steam Frame

> [!IMPORTANT]
> Fellyjin is an unofficial personal project, a client for Jellyfin servers. It is not affiliated with the Jellyfin
> Project or Valve, and was built largely with [Claude Code](https://claude.com/claude-code). See the
> [main README](../README.md) for details.

Fellyjin is a native ARM64 Linux client for Jellyfin servers, built for SteamOS on the Valve Steam Frame and forked
from Jellyfin for Android TV. Movies and shows play on a huge virtual cinema screen through OpenXR (SteamVR). If
OpenXR is not available, playback falls back to a fullscreen window.

This lives next to the Android app in the same repository, but it is a separate Gradle build: the Android app depends
on Android-only APIs (Leanback, Media3, Android services), so the UI and the player were rewritten for the desktop JVM.
The Jellyfin Kotlin SDK and the playback approach (playback info, play methods, progress reporting) carry over from
the Android app.

## Architecture

| Area      | Android TV app                     | Steam Frame app                                        |
|-----------|------------------------------------|--------------------------------------------------------|
| UI        | Leanback + Jetpack Compose (Android) | Compose Multiplatform (desktop JVM), 10-foot layout  |
| API       | Jellyfin Kotlin SDK                | Jellyfin Kotlin SDK (JVM)                              |
| Playback  | Media3 / ExoPlayer                 | libmpv (ffmpeg, libass, hardware decoding) through JNA |
| Output    | Android surface                    | OpenXR composition layers (LWJGL), or a mpv window    |
| Input     | D-pad remote                       | Steam Input (keyboard/mouse/laser) + OpenXR actions    |

```
src/main/kotlin/app/fellyjin/
├── Main.kt                  Window setup, fullscreen under gamescope
├── data/                    Config (JSON in ~/.config), Jellyfin SDK repository
├── ui/                      Compose screens: server, login (Quick Connect), home, library, details, settings
├── playback/                Stream resolving, playback thread, progress reporting, flat window output
├── mpv/                     libmpv client + OpenGL render API bindings
└── vr/                      OpenXR cinema: session, swapchain, cylinder/quad layers, controller actions
```

### How the VR cinema works

1. A hidden GLFW window provides an OpenGL (GLX) context on the playback thread. SteamVR's OpenXR OpenGL binding on
   Linux requires GLX, so the app uses XWayland when running under gamescope.
2. mpv runs with `vo=libmpv` and renders every new video frame straight into an OpenXR swapchain image.
3. The image is submitted as an `XR_KHR_composition_layer_cylinder` layer: a curved screen around the viewer. If the
   runtime lacks the extension, it uses a flat quad layer. Composition layers are resampled by the compositor at
   display resolution, which keeps text and fine detail sharp, unlike drawing a textured quad yourself.
4. 3D movies (half/full side-by-side and top-bottom, read from the Jellyfin `Video3DFormat`) are shown in stereo:
   each eye gets its own layer with its half of the frame.
5. mpv's OSD (seek bar, track names) and subtitles are rendered into the video frame, so they appear on the big
   screen.
6. Around the screen, a movie theater is rendered as a projection layer: violet velvet curtains, fabric walls, mint aisle
   step lights and a projection booth. It is lit by the film itself. Three light zones are sampled from every video frame
   on the GPU, so a sunset warms the left wall and a night scene leaves the room dark. The house lights dim over a
   few seconds when the movie starts and come back up when you pause. Switch to *Black void* or *Passthrough* in
   the settings.

### For video fans

- **Format badges** on the detail page: 4K, Dolby Vision / HDR10 / HDR10+, HEVC / AV1, Dolby Atmos / DTS:X /
  TrueHD / DTS-HD MA, 5.1 / 7.1 and 3D.
- **Choose audio and subtitles before playing**, like a disc menu. The choice is passed to the server (for
  transcodes) and mapped to mpv's tracks for direct play.
- **Media info card in VR** (Menu button): codec, resolution, frame rate, color primaries and transfer, audio
  format and channels, the active hardware decoder, dropped frames and the display rate.
- **Preview cinema** (Settings → VR cinema) opens the theater with a colorful test video, without a server, to try
  screen sizes and check that VR works.

### Playing in a window

Without VR (or when it can't start), mpv draws into a surface inside the Fellyjin window (`--wid`) instead of
opening its own window. Gamescope shows only one window per app, so a separate player window would play unseen
with only the audio audible. mpv draws with Vulkan (the Frame's native driver) and falls back to EGL when
Vulkan can't open; `FELLYJIN_GPU_CONTEXT=x11egl` forces OpenGL.

Point at the video with the laser and mpv's on-screen controller appears: seek bar, play/pause, chapters, audio
and subtitle tracks and volume. It hides again after 3 seconds. With the controller or keyboard: A / Space pauses,
B / Escape stops, left/right seeks, up/down changes the volume.

### Starting VR

The VR cinema prefers an EGL context (`XR_MNDX_egl_enable`) and falls back to GLX
(`XR_KHR_opengl_enable` Xlib binding). `FELLYJIN_XR_GL=egl|glx` forces one. If VR can't start, playback continues
in a window, and Settings → VR status shows the exact reason, the runtime and the active features.

### Curved screen without cylinder layers

SteamVR on the Steam Frame doesn't offer `XR_KHR_composition_layer_cylinder`. There, Fellyjin draws the curved
screen itself as a projection layer: per eye, one full screen pass intersects the view ray with the screen cylinder
and samples the video from a mipmapped, anisotropically filtered texture. That texture is updated once per video
frame, which keeps 4K free of shimmer on a screen smaller than the video. Edges are anti-aliased, and the
surroundings stay transparent so the theater shows through. Runtimes that do offer cylinder layers keep using
them. `FELLYJIN_SCREEN=projection` forces the app-drawn screen.

### Energy and picture quality

- **Refresh rate: Energy saving** (default) picks the lowest headset refresh rate of at least 90 Hz that is a whole
  multiple of the film's frame rate (24p → 120 Hz, 30p → 90 Hz), so frames stay evenly paced while the GPU draws
  fewer frames. **Maximum** always uses the highest rate. Both need the runtime's `XR_FB_display_refresh_rate`;
  otherwise SteamVR's own setting applies.
- The app-drawn screen only shades the pixels around the screen (scissor) instead of the whole view, and the theater
  renders at half resolution.
- The screen texture is sRGB, so filtering and mipmaps happen in linear light. Fine detail and colors stay right
  when a 4K frame is minified, with up to 16× anisotropic filtering.
- mpv upscales sources up to 1440p with sigmoid upscaling, anti-ringing and light debanding. 4K sources take the
  cheap path.
- The JVM runs generational ZGC (sub-millisecond pauses, so garbage collection never stalls the VR frame loop) and
  returns unused memory to the system.
- The gamepad is polled at 60 Hz with a controller and 4 Hz without one.
- **Dolby Vision profile 5** (no HDR10/SDR base layer) is converted by the server, because mpv's OpenGL render path
  can't apply Dolby's reshaping and would show green and purple colors. Profiles 7 and 8 play directly. For correct
  colors, enable tone mapping in the Jellyfin server's transcoding settings.
- Hardware decoding: HEVC, H.264 and VP9 are decoded by the Snapdragon (V4L2 M2M). AV1 is decoded in software,
  because the bundled ffmpeg has no V4L2 AV1 decoder.

### Smoothness at 144 Hz

- The app asks for the headset's highest refresh rate through `XR_FB_display_refresh_rate` when SteamVR exposes it.
  Otherwise the rate follows the SteamVR setting. The real display period from `xrWaitFrame` is passed to mpv, and
  24 fps film divides 144 Hz evenly (6 refreshes per frame), so pacing stays even.
- mpv renders on its own thread with a shared OpenGL context, into a ring of three textures guarded by GPU fences.
  The frame loop only blits the newest finished frame into the swapchain (well under 1 ms), so a heavy 4K frame
  can never make the headset miss a 144 Hz refresh. Every other display frame just resubmits the layers.
- 4K is decoded by the Snapdragon's hardware decoder through V4L2 M2M (`v4l2m2m-copy`, falling back to software).
  For 4K sources the app switches to the cheap render path: no debanding, bilinear chroma, no HDR peak detection.
  These matter little at 4K and cost the most GPU time. Generous buffering (60 s, 1 GiB) absorbs Wi-Fi dips at 4K
  remux bitrates.
- Settings → Performance overlay shows mpv's statistics (decoder, frame timings, dropped frames) on the screen.
- The theater is one analytic full-screen shader pass per eye at half resolution, with no meshes and no texture
  reads in the fragment shader. The light zones come from a 64×36 mipmapped copy of the frame, blitted on the GPU
  without a CPU readback.
- The frame loop allocates nothing on the JVM heap: OpenXR structs live on LWJGL's stack, so no GC pauses.

Picture quality: *High* (default) lets mpv upscale to 4K with spline36 and debanding before the compositor samples
it. That runs at video frame rate, so it costs nothing per display frame. *Balanced* uses the native resolution
for the lowest GPU load. In both modes mpv converts BT.1886 to the sRGB swapchain, so shadows look as mastered, and
HDR is tone-mapped.

Screen presets (Settings → VR cinema):

| Preset      | Width | Distance | Horizontal FOV |
|-------------|-------|----------|----------------|
| Living room | 2.2 m | 3 m      | 40°            |
| Cinema      | 12 m  | 12 m     | 53°            |
| Huge screen | 24 m  | 15 m     | 77° (default)  |
| IMAX        | 30 m  | 14 m     | 93°            |

### Controller

In VR the app uses the native Steam Frame profile (`XR_VALVE_frame_controller_interaction`,
`/interaction_profiles/valve/frame_controller_valve`) with the default layout:

| Steam Frame controller        | Action                              |
|-------------------------------|-------------------------------------|
| A / trigger                   | Play / pause (shows the seek bar)   |
| B / View                      | Stop and return to the library      |
| Menu                          | Show progress                       |
| D-pad ← → / stick left, right | Seek back 10 s / forward 30 s (stick repeats, 60 s when held) |
| D-pad ↑ ↓ / stick up, down    | Move the screen closer / further    |
| Bumpers                       | Previous / next chapter             |
| X / Y                         | Next subtitle / audio track         |
| Stick click                   | Recenter the screen                 |

Bindings for Index, Touch and the simple controller are included too, and SteamVR's binding UI can remap everything.

The library UI is a normal window on SteamOS' virtual screen. The default Steam Frame controls reach it as a
gamepad, which the app reads through SDL2 (bundled with libmpv): D-pad or left stick move the focus, A selects and
B goes back. This is handled inside Compose, so it works even when gamescope doesn't give the Java window keyboard
focus. A message appears when a controller is detected. In VR, the headset briefly shows which controller profile
SteamVR selected (e.g. "Controller: Steam Frame"). The laser pointer works as a mouse. For login, use Quick Connect so you don't have to type a password
in VR.

## Building

Requires JDK 21+. Building on Linux ARM64 produces a package for the Steam Frame:

```bash
sudo apt install libmpv2 patchelf        # or your distro's equivalent
steamframe/packaging/bundle-libmpv.sh    # copies libmpv + dependencies into the app resources
./gradlew -p steamframe test packageTarball
```

The result is `steamframe/build/dist/fellyjin-steamframe-<version>-linux-arm64.tar.gz`. It includes a jlinked Java
runtime, so SteamOS needs nothing extra. The [Steam Frame / Build](../.github/workflows/steamframe-build.yaml)
workflow does this on GitHub's ARM64 runners and uploads the tarball as an artifact.

For development on any OS with a JDK (and libmpv installed for playback):

```bash
./gradlew -p steamframe run
```

## Installing on the Steam Frame

1. Switch to Desktop Mode and extract the tarball, then run `./fellyjin-steamframe/install.sh`. This installs into
   `~/.local/share/fellyjin-steamframe`, since the root filesystem is read-only.
2. In Steam, choose *Add a Non-Steam Game* and select *Jellyfin*.
3. Launch it from Gaming Mode. Playback starts SteamVR's OpenXR session automatically.

## Configuration

The old `JELLYFIN_*` variable names still work. The settings folder of builds from before the rename is moved over
automatically.

Settings are stored in `~/.config/fellyjin-steamframe/config.json`, which holds the access token and is readable only by
the current user. mpv can be tuned with `~/.config/fellyjin-steamframe/mpv/mpv.conf`.

Environment variables:

| Variable                 | Purpose                                                       |
|--------------------------|---------------------------------------------------------------|
| `FELLYJIN_MPV_LIBRARY`   | Path to a specific `libmpv.so`                                |
| `FELLYJIN_OPENXR_LOADER` | Path to a specific `libopenxr_loader.so` (LWJGL bundles one)  |
| `FELLYJIN_XR_FLIP_Y=0`   | Use if a runtime shows the video upside down                  |
| `FELLYJIN_FULLSCREEN=1`  | Start fullscreen outside gamescope (toggle with F11)          |

## Status and known gaps

- The app compiles, and the UI and theater render in offscreen snapshot tests (`build/snapshots`). The theater
  test runs on Mesa under Xvfb in CI. The libmpv binding test
  runs wherever libmpv is installed, including the CI workflow. The OpenXR path is written against the OpenXR 1.0 spec and SteamVR's OpenGL/Xlib binding,
  but has not run on Steam Frame hardware yet. Expect to tune it on the device: the swapchain format, the
  orientation (`FELLYJIN_XR_FLIP_Y`) and the controller bindings for the Steam Frame's own controllers.
- If SteamVR on the Frame turns out to support only Vulkan sessions, the mpv OpenGL output needs an OpenGL→Vulkan
  interop step (`GL_EXT_memory_object`). The flat window fallback keeps playback working in the meantime.
- Not ported yet from the Android app: music playback, Live TV, search, SyncPlay, trickplay previews, media segment
  skipping (intro/credits), and per-item audio/subtitle selection before playback. During playback you can switch
  tracks with the controller.
