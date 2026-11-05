#!/bin/sh
# Installs MD Edit for the current Linux user:
#   - the `md-edit` command in ~/.local/bin
#   - an app-menu entry (~/.local/share/applications/md-edit.desktop)
#   - a desktop icon (in your Desktop folder)
#
# Usage:  ./install-linux.sh              install (build dist/md-edit.jar first with ./build.sh)
#         ./install-linux.sh --uninstall  remove the command, menu entry and desktop icon
#
# Paths point at this project folder; run it again if you move the folder.
set -eu

DIR=$(cd "$(dirname "$0")" && pwd)
BIN="$HOME/.local/bin"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
DESKTOP_DIR=$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")

if [ "${1:-}" = "--uninstall" ]; then
    rm -f "$BIN/md-edit" "$APPS/md-edit.desktop" "$DESKTOP_DIR/md-edit.desktop"
    command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APPS" 2>/dev/null || true
    echo "MD Edit command, menu entry and desktop icon removed."
    exit 0
fi

if [ ! -f "$DIR/dist/md-edit.jar" ]; then
    echo "Note: $DIR/dist/md-edit.jar doesn't exist yet. Build it with ./build.sh before launching."
fi

chmod +x "$DIR/md-edit"
mkdir -p "$BIN" "$APPS"
ln -sf "$DIR/md-edit" "$BIN/md-edit"

ENTRY=$(mktemp)
cat > "$ENTRY" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=MD Edit
GenericName=Markdown Editor
Comment=Edit Markdown with a live styled preview
Exec="$DIR/md-edit" %f
Icon=$DIR/src/main/resources/com/mdedit/md-edit.png
Terminal=false
Categories=Utility;TextEditor;
MimeType=text/markdown;text/x-markdown;
Keywords=markdown;md;editor;preview;
StartupNotify=true
StartupWMClass=com-mdedit-Main
EOF

install -m 755 "$ENTRY" "$APPS/md-edit.desktop"
if [ -d "$DESKTOP_DIR" ]; then
    install -m 755 "$ENTRY" "$DESKTOP_DIR/md-edit.desktop"
    # GNOME only launches desktop icons that are marked trusted.
    command -v gio >/dev/null 2>&1 && gio set "$DESKTOP_DIR/md-edit.desktop" metadata::trusted true 2>/dev/null || true
fi
rm -f "$ENTRY"
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APPS" 2>/dev/null || true

echo "Installed:"
echo "  command       $BIN/md-edit"
echo "  menu entry    $APPS/md-edit.desktop"
[ -d "$DESKTOP_DIR" ] && echo "  desktop icon  $DESKTOP_DIR/md-edit.desktop"
case ":$PATH:" in
    *":$BIN:"*) ;;
    *) echo "Add $BIN to your PATH to use the md-edit command from a terminal." ;;
esac
