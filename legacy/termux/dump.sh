#!/data/data/com.termux/files/usr/bin/bash
# DeltaPatch converter - step 3: decompile the PC file and the Android file and diff them
set -e
echo "=== DeltaPatch code dump ==="
proot-distro login ubuntu --bind /storage/emulated/0:/sdcard -- bash /sdcard/DeltaPatch/termux/inner-dump.sh
echo "=== dump finished: /sdcard/DeltaPatch/dump/hadrian_edits.diff ==="
