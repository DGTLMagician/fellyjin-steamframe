#!/usr/bin/env bash
# Installs Fellyjin for the current user (SteamOS has a read-only root, so everything goes into the home directory).
# Run from the extracted tarball: ./fellyjin-steamframe/install.sh
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
DEST="$DATA/fellyjin-steamframe"
BIN="$HOME/.local/bin"
APPS="$DATA/applications"
ICONS="$DATA/icons/hicolor/256x256/apps"

if [[ "$SRC" != "$DEST" ]]; then
	rm -rf "$DEST"
	mkdir -p "$(dirname "$DEST")"
	cp -a "$SRC" "$DEST"
fi

mkdir -p "$BIN" "$APPS" "$ICONS"
ln -sf "$DEST/bin/fellyjin-steamframe" "$BIN/fellyjin-steamframe"
cp "$DEST/icon.png" "$ICONS/fellyjin-steamframe.png"
sed "s|^Exec=.*|Exec=$DEST/bin/fellyjin-steamframe|" "$DEST/fellyjin-steamframe.desktop" > "$APPS/fellyjin-steamframe.desktop"

# Remove an install from before the rename (settings are migrated by the app on first start)
OLD="$DATA/jellyfin-steamframe"
if [[ -d "$OLD" && -x "$OLD/bin/jellyfin-steamframe" ]]; then
	rm -rf "$OLD"
	rm -f "$BIN/jellyfin-steamframe" "$APPS/jellyfin-steamframe.desktop" "$ICONS/jellyfin-steamframe.png"
	echo "Removed the old Jellyfin Steam Frame install, re-add the game in Steam (see below)"
fi

echo "Installed Fellyjin to $DEST"
echo "To launch it from SteamOS gaming mode: Steam > Add a Non-Steam Game > Fellyjin"
