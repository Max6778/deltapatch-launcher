#!/data/data/com.termux/files/usr/bin/bash
# DeltaPatch - make the patched game file with xdelta3 (run inside Termux, started by the launcher)
B=/sdcard/DeltaPatch
mkdir -p "$B/out"
fail() { echo "ERROR $1" > "$B/out/result.txt"; echo "ERROR: $1"; exit 1; }
echo "RUNNING" > "$B/out/result.txt"
if [ ! -r "$B" ]; then
  echo "Termux needs storage access - tap Allow."
  termux-setup-storage
  sleep 8
fi
CH=3
[ -f "$B/termux/job.conf" ] && . "$B/termux/job.conf"
[ -f "$B/pc/data.win" ] || fail "missing /sdcard/DeltaPatch/pc/data.win (use 'Copy files' first)"
[ -f "$B/pc/patch.xdelta" ] || fail "missing /sdcard/DeltaPatch/pc/patch.xdelta (use 'Copy files' first)"
if ! command -v xdelta3 > /dev/null 2>&1; then
  pkg update -y
  pkg install -y xdelta3 || fail "could not install xdelta3"
fi
rm -f "$B/out/chapter$CH.droid"
xdelta3 -d -f -B 134217728 -s "$B/pc/data.win" "$B/pc/patch.xdelta" "$B/out/chapter$CH.droid" || fail "xdelta3 failed: wrong PC data.win version, or not enough free space"
# Android adaptation hook (added later): runs if the converter step is installed
if [ -f "$B/termux/adapt.sh" ]; then
  bash "$B/termux/adapt.sh" "$B/out/chapter$CH.droid" || fail "adapt step failed"
fi
ls -l "$B/out/chapter$CH.droid"
echo "OK" > "$B/out/result.txt"
echo "Done. Go back to the launcher."
