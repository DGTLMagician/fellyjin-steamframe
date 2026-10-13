<p align="center">
<img alt="Fellyjin icon" src="steamframe/packaging/icon.png" width="160"/>
</p>

<h1 align="center">fellyjin</h1>
<h3 align="center">Your Jellyfin library, cinema sized, on the Steam Frame</h3>

<p align="center">
<a href="LICENSE"><img alt="GPL 2.0 License" src="https://img.shields.io/badge/license-GPL--2.0-8B5CF6.svg"/></a>
<img alt="Unofficial" src="https://img.shields.io/badge/status-unofficial%20%2F%20experimental-FF5C7A.svg"/>
<img alt="Platform" src="https://img.shields.io/badge/platform-SteamOS%20ARM64-2EE6C5.svg"/>
</p>

> [!IMPORTANT]
> **Fellyjin is an unofficial, personal project** by [DGTLMagician](https://github.com/DGTLMagician). It is a client
> for [Jellyfin](https://jellyfin.org) media servers, forked from
> [Jellyfin for Android TV](https://github.com/jellyfin/jellyfin-androidtv), and it is **not affiliated with, endorsed
> by or supported by the Jellyfin Project or Valve**. Please don't report issues with Fellyjin to the Jellyfin
> developers.

> [!NOTE]
> Large parts of Fellyjin were written with [Claude Code](https://claude.com/claude-code), Anthropic's AI coding
> assistant, under the direction of the maintainer. The code has been compiled and tested in automated tests and
> containers, but the VR parts are experimental and still being tested on real Steam Frame hardware. Expect rough
> edges.

## What is Fellyjin?

Fellyjin is a native ARM64 Linux client for SteamOS on the Valve Steam Frame. It plays the movies and shows on your
own Jellyfin server on a **huge virtual cinema screen**:

- 🎬 **VR cinema through OpenXR (SteamVR):** a curved screen up to IMAX size, rendered as a sharp composition layer.
  Stereoscopic 3D movies play in real 3D.
- 🍿 **Movie theater environment:** an auditorium with violet velvet curtains and mint aisle lights, lit by the film
  itself. The house lights dim when the movie starts and come back up when you pause.
- ⚡ **Smooth at 144 Hz:** mpv renders on its own thread, the frame loop never waits for a video frame, and 4K is
  decoded by the Snapdragon's hardware decoder.
- 🎛️ **Steam Frame controls:** the native controller profile in VR, and gamepad navigation in the library.
- ✨ **A fresh 10-foot UI:** Quick Connect login (no typing in VR), a spotlight with title logos, and backdrops that
  follow your focus.
- 🪟 A fullscreen window fallback when VR is not available.

The app lives in [`steamframe/`](steamframe/). See [steamframe/README.md](steamframe/README.md) for the
architecture, controls, configuration and known limitations.

## Installing

1. Download `fellyjin-steamframe-<version>-linux-arm64.tar.gz` from the latest run of the
   [Fellyjin / Build](../../actions/workflows/steamframe-build.yaml) workflow, or build it yourself (below).
2. On the Steam Frame, switch to Desktop Mode, extract the archive and run:
   ```shell
   ./fellyjin-steamframe/install.sh
   ```
3. In Steam choose *Add a Non-Steam Game* → *Fellyjin*, and start it from Gaming Mode.

Upgrading from a build that was still called *Jellyfin Steam Frame*? The installer removes the old version and
Fellyjin keeps your server, login and settings. Only the Steam shortcut has to be added again.

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
before with `./gradlew assembleDebug`. Fellyjin is a separate Gradle build next to it. It uses the official
Jellyfin Kotlin SDK to talk to your server, but replaces the Android UI and player, which don't run on SteamOS.

All credit for Jellyfin and the Android TV app goes to the [Jellyfin contributors](https://github.com/jellyfin/jellyfin-androidtv/graphs/contributors).
If you enjoy Jellyfin, consider [supporting the project](https://opencollective.com/jellyfin).

## License

Like the upstream project, Fellyjin is licensed under the [GNU General Public License v2.0](LICENSE). Jellyfin is a
trademark of the Jellyfin Project. Steam and Steam Frame are trademarks of Valve Corporation. Fellyjin is not
associated with either.
