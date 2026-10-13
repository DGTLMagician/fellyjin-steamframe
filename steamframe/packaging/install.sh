#!/usr/bin/env bash
# Installs Jellyfin for the current user (SteamOS has a read-only root, so everything goes into the home directory).
# Run from the extracted tarball: ./jellyfin-steamframe/install.sh
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="${XDG_DATA_HOME:-$HOME/.local/share}/jellyfin-steamframe"
BIN="$HOME/.local/bin"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICONS="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/256x256/apps"

if [[ "$SRC" != "$DEST" ]]; then
	rm -rf "$DEST"
	mkdir -p "$(dirname "$DEST")"
	cp -a "$SRC" "$DEST"
fi

mkdir -p "$BIN" "$APPS" "$ICONS"
ln -sf "$DEST/bin/jellyfin-steamframe" "$BIN/jellyfin-steamframe"
cp "$DEST/icon.png" "$ICONS/jellyfin-steamframe.png"
sed "s|^Exec=.*|Exec=$DEST/bin/jellyfin-steamframe|" "$DEST/jellyfin-steamframe.desktop" > "$APPS/jellyfin-steamframe.desktop"

echo "Installed to $DEST"
echo "To launch it from SteamOS gaming mode: Steam > Add a Non-Steam Game > Jellyfin"
