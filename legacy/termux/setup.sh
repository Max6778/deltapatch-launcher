#!/data/data/com.termux/files/usr/bin/bash
# DeltaPatch converter - step 1: install everything (run inside Termux, started by the launcher)
set -e
echo "=== DeltaPatch converter setup ==="
if [ ! -r /sdcard/DeltaPatch ]; then
  echo "Termux has no storage access yet. Allowing it now (tap Allow)..."
  termux-setup-storage || true
  sleep 8
fi
mkdir -p /sdcard/DeltaPatch/dump
pkg update -y
pkg install -y proot-distro curl xdelta3 unzip
proot-distro install ubuntu || true
proot-distro login ubuntu --bind /storage/emulated/0:/sdcard -- bash /sdcard/DeltaPatch/termux/inner-setup.sh
echo "=== setup finished ==="
