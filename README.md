<h1 align="center">Jellyfin for Steam Frame</h1>
<h3 align="center">An unofficial, personal fork of <a href="https://github.com/jellyfin/jellyfin-androidtv">Jellyfin for Android TV</a></h3>

---

<p align="center">
<img alt="Logo banner" src="https://raw.githubusercontent.com/jellyfin/jellyfin-ux/master/branding/SVG/banner-logo-solid.svg?sanitize=true"/>
<br/><br/>
<a href="LICENSE">
<img alt="GPL 2.0 License" src="https://img.shields.io/badge/license-GPL--2.0-blue.svg"/>
</a>
<img alt="Unofficial" src="https://img.shields.io/badge/status-unofficial%20%2F%20experimental-orange.svg"/>
<img alt="Platform" src="https://img.shields.io/badge/platform-SteamOS%20ARM64-1b2838.svg"/>
</p>

> [!IMPORTANT]
> **This is not an official Jellyfin project.** It is a personal fork maintained by
> [DGTLMagician](https://github.com/DGTLMagician). It is not affiliated with, endorsed by or supported by the
> [Jellyfin Project](https://jellyfin.org) or Valve. Please do not report issues from this fork to the Jellyfin
> developers. For the official Android TV app, go to
> [jellyfin/jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv).

> [!NOTE]
> Large parts of this fork were written with [Claude Code](https://claude.com/claude-code), Anthropic's AI coding
> assistant, under the direction of the maintainer. The code has been compiled and tested in automated tests and
> containers, but the VR parts are experimental and still being tested on real Steam Frame hardware. Expect rough
> edges.

## What is this?

Jellyfin for Steam Frame is a native ARM64 Linux client for SteamOS on the Valve Steam Frame. Your Jellyfin movies
and shows play on a **huge virtual cinema screen**:

- **VR cinema through OpenXR (SteamVR):** a curved screen up to IMAX size, rendered as a sharp composition layer.
  Stereoscopic 3D movies play in real 3D.
- **Movie theater environment:** an auditorium with velvet curtains and aisle lights, lit by the film itself. The
  house lights dim when the movie starts and come back up when you pause.
- **Smooth playback:** requests the headset's highest refresh rate (144 Hz), and 24 fps films are paced evenly.
- **mpv playback:** direct play of practically every format, with hardware decoding, subtitles, debanding and
  upscaling to 4K.
- **10-foot UI** with Quick Connect login (no typing in VR), continue watching, next up and a backdrop that follows
  your focus.
- **Steam Frame controls:** the native controller profile in VR, plus gamepad navigation in the library.
- A fullscreen window fallback when VR is not available.

The app lives in [`steamframe/`](steamframe/). See [steamframe/README.md](steamframe/README.md) for the
architecture, controls, configuration and known limitations.

## Installing

1. Download `jellyfin-steamframe-<version>-linux-arm64.tar.gz` from the latest run of the
   [Steam Frame / Build](../../actions/workflows/steamframe-build.yaml) workflow, or build it yourself (below).
2. On the Steam Frame, switch to Desktop Mode, extract the archive and run:
   ```shell
   ./jellyfin-steamframe/install.sh
   ```
3. In Steam choose *Add a Non-Steam Game* → *Jellyfin*, and start it from Gaming Mode.

## Building

Building requires JDK 21+. Build on Linux ARM64 to produce a package for the Steam Frame:

```shell
sudo apt install libmpv2 patchelf
steamframe/packaging/bundle-libmpv.sh
./gradlew -p steamframe test packageTarball
```

The tarball ends up in `steamframe/build/dist/`. It contains its own Java runtime and libmpv.

## Relation to Jellyfin for Android TV

This repository is a fork of [jellyfin/jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv). The
original Android TV app is still included unchanged (`app/`, `playback/`, `design/`, `preference/`) and builds as
before with `./gradlew assembleDebug`. The Steam Frame client is a separate Gradle build next to it. It reuses the
Jellyfin Kotlin SDK and the playback approach, but replaces the Android UI and player, which don't run on SteamOS.

All credit for Jellyfin and the Android TV app goes to the [Jellyfin contributors](https://github.com/jellyfin/jellyfin-androidtv/graphs/contributors).
If you enjoy Jellyfin, consider [supporting the project](https://opencollective.com/jellyfin).

## License

Like the upstream project, this fork is licensed under the [GNU General Public License v2.0](LICENSE). Jellyfin and
the Jellyfin logo are trademarks of the Jellyfin Project. Steam and Steam Frame are trademarks of Valve Corporation.
