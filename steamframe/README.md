# Jellyfin for Steam Frame

A native ARM64 Linux port of Jellyfin for Android TV, built for SteamOS on the Valve Steam Frame. Movies and shows
play on a huge virtual cinema screen through OpenXR (SteamVR). If OpenXR is not available, playback falls back to a
fullscreen window.

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
src/main/kotlin/org/jellyfin/steamframe/
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
6. Around the screen, a movie theater is rendered as a projection layer: velvet curtains, fabric walls, aisle step
   lights and a projection booth. It is lit by the film itself. Three light zones are sampled from every video frame
   on the GPU, so a sunset warms the left wall and a night scene leaves the room dark. The house lights dim over a
   few seconds when the movie starts and come back up when you pause. Switch to *Black void* or *Passthrough* in
   the settings.

### Smoothness at 144 Hz

- The app asks for the headset's highest refresh rate through `XR_FB_display_refresh_rate` when SteamVR exposes it.
  Otherwise the rate follows the SteamVR setting. The real display period from `xrWaitFrame` is passed to mpv, and
  24 fps film divides 144 Hz evenly (6 refreshes per frame), so pacing stays even.
- mpv only renders when a new video frame is due (24-60 times per second). Every other display frame just
  resubmits the layers, and the compositor handles head motion.
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
B goes back. The laser pointer works as a mouse. For login, use Quick Connect so you don't have to type a password
in VR.

## Building

Requires JDK 21+. Building on Linux ARM64 produces a package for the Steam Frame:

```bash
sudo apt install libmpv2 patchelf        # or your distro's equivalent
steamframe/packaging/bundle-libmpv.sh    # copies libmpv + dependencies into the app resources
./gradlew -p steamframe test packageTarball
```

The result is `steamframe/build/dist/jellyfin-steamframe-<version>-linux-arm64.tar.gz`. It includes a jlinked Java
runtime, so SteamOS needs nothing extra. The [Steam Frame / Build](../.github/workflows/steamframe-build.yaml)
workflow does this on GitHub's ARM64 runners and uploads the tarball as an artifact.

For development on any OS with a JDK (and libmpv installed for playback):

```bash
./gradlew -p steamframe run
```

## Installing on the Steam Frame

1. Switch to Desktop Mode and extract the tarball, then run `./jellyfin-steamframe/install.sh`. This installs into
   `~/.local/share/jellyfin-steamframe`, since the root filesystem is read-only.
2. In Steam, choose *Add a Non-Steam Game* and select *Jellyfin*.
3. Launch it from Gaming Mode. Playback starts SteamVR's OpenXR session automatically.

## Configuration

Settings are stored in `~/.config/jellyfin-steamframe/config.json`, which holds the access token and is readable only by
the current user. mpv can be tuned with `~/.config/jellyfin-steamframe/mpv/mpv.conf`.

Environment variables:

| Variable                 | Purpose                                                       |
|--------------------------|---------------------------------------------------------------|
| `JELLYFIN_MPV_LIBRARY`   | Path to a specific `libmpv.so`                                |
| `JELLYFIN_OPENXR_LOADER` | Path to a specific `libopenxr_loader.so` (LWJGL bundles one)  |
| `JELLYFIN_XR_FLIP_Y=0`   | Use if a runtime shows the video upside down                  |
| `JELLYFIN_FULLSCREEN=1`  | Start fullscreen outside gamescope (toggle with F11)          |

## Status and known gaps

- The app compiles, and the UI and theater render in offscreen snapshot tests (`build/snapshots`). The theater
  test runs on Mesa under Xvfb in CI. The libmpv binding test
  runs wherever libmpv is installed, including the CI workflow. The OpenXR path is written against the OpenXR 1.0 spec and SteamVR's OpenGL/Xlib binding,
  but has not run on Steam Frame hardware yet. Expect to tune it on the device: the swapchain format, the
  orientation (`JELLYFIN_XR_FLIP_Y`) and the controller bindings for the Steam Frame's own controllers.
- If SteamVR on the Frame turns out to support only Vulkan sessions, the mpv OpenGL output needs an OpenGL→Vulkan
  interop step (`GL_EXT_memory_object`). The flat window fallback keeps playback working in the meantime.
- Not ported yet from the Android app: music playback, Live TV, search, SyncPlay, trickplay previews, media segment
  skipping (intro/credits), and per-item audio/subtitle selection before playback. During playback you can switch
  tracks with the controller.
