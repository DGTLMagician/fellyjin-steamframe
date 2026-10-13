#!/usr/bin/env bash
# Copies libmpv and the libraries it depends on into the app resources so the app runs on SteamOS, which does not
# ship libmpv and has a read-only root filesystem. Run on the same architecture as the target (linux-arm64 for the
# Steam Frame) after installing libmpv, for example on Ubuntu: apt install libmpv2 patchelf
#
# Usage: packaging/bundle-libmpv.sh [output dir]
# The default output dir is picked up by the Compose distribution (appResourcesRootDir).
set -euo pipefail

cd "$(dirname "$0")/.."

case "$(uname -m)" in
	aarch64 | arm64) ARCH=arm64 ;;
	x86_64) ARCH=x64 ;;
	*) echo "Unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac

OUT="${1:-build/native-bundle/linux-$ARCH/lib}"
LIBMPV="${LIBMPV:-$(ldconfig -p | awk '/libmpv\.so\.2 / { print $NF; exit }')}"

if [[ -z "$LIBMPV" || ! -f "$LIBMPV" ]]; then
	echo "libmpv.so.2 not found, install libmpv2 or set LIBMPV" >&2
	exit 1
fi

command -v patchelf > /dev/null || { echo "patchelf is required" >&2; exit 1; }

# Libraries provided by SteamOS itself. They are tied to the graphics driver, audio server or display server and
# must come from the host. Based on the AppImage exclude list. Other X11 extension libraries are bundled.
EXCLUDE_REGEX='^(ld-linux.*|libc|libm|libdl|libpthread|librt|libresolv|libutil|libgcc_s|libstdc\+\+|libGL.*|libEGL|libGLX.*|libGLdispatch|libOpenGL|libgbm|libdrm.*|libvulkan|libX11|libX11-xcb|libxcb|libxcb-dri[23]|libxcb-glx|libxcb-present|libxcb-sync|libxcb-randr|libxshmfence|libwayland-.*|libxkbcommon.*|libasound|libpulse.*|libpipewire-.*|libudev|libsystemd|libdbus-1|libfontconfig|libfreetype|libselinux|libgcrypt|libgpg-error)\.so'

rm -rf "$OUT"
mkdir -p "$OUT"

echo "Bundling $LIBMPV into $OUT"
cp -L "$LIBMPV" "$OUT/libmpv.so.2"

ldd "$LIBMPV" | awk '/=> \// { print $1, $3 }' | while read -r name path; do
	if [[ "$name" =~ $EXCLUDE_REGEX ]]; then
		continue
	fi
	cp -L "$path" "$OUT/$name"
done

# Make the bundled libraries find each other
for lib in "$OUT"/*.so*; do
	chmod u+w "$lib"
	patchelf --set-rpath '$ORIGIN' "$lib"
done

echo "Bundled $(ls "$OUT" | wc -l) libraries ($(du -sh "$OUT" | cut -f1))"
