#!/bin/sh
# Install the NiuBackup headless receiver as a systemd *user* service.
#
# Usage: ./install-autostart.sh /path/to/NiuBackup-app-dir
#   The app dir is the jpackage app-image output (launcher at bin/NiuBackup).
#   Before first start, log in once so account state exists:
#     <app-dir>/bin/NiuBackup --login --server <url> --account-user <u> \
#         --account-pass <p> --data-dir ~/.niubackup
#
# Linux mesh privilege note: easytier-core needs CAP_NET_ADMIN to create its
# TUN device. This script offers to setcap the bundled binary (requires
# sudo). Without it the receiver starts but the sidecar cannot create the
# tunnel. Alternative: run the whole service as a *system* service instead.
set -eu

APP_DIR=${1:?usage: install-autostart.sh /path/to/NiuBackup-app-dir}
APP_DIR=$(cd "$APP_DIR" && pwd)
UNIT_DIR="$HOME/.config/systemd/user"
UNIT="$UNIT_DIR/niubackup.service"

mkdir -p "$UNIT_DIR"
sed "s|__APP_DIR__|$APP_DIR|g" "$(dirname "$0")/niubackup.service" > "$UNIT"
echo "wrote $UNIT"

ET_BIN="$APP_DIR/lib/app/easytier-core"
if [ -f "$ET_BIN" ]; then
    if command -v setcap >/dev/null 2>&1; then
        echo "granting CAP_NET_ADMIN to the bundled easytier-core (sudo)..."
        sudo setcap cap_net_admin,cap_net_bind_service=+ep "$ET_BIN" || \
            echo "WARNING: setcap failed; the mesh sidecar will not be able to create a TUN device"
    else
        echo "WARNING: setcap not found; grant CAP_NET_ADMIN to $ET_BIN manually"
    fi
else
    echo "WARNING: easytier-core not found at $ET_BIN (mesh sidecar will rely on PATH)"
fi

systemctl --user daemon-reload
systemctl --user enable --now niubackup.service
echo "niubackup.service enabled. Status: systemctl --user status niubackup"
