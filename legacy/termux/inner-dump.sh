set -e
export DOTNET_ROOT=/opt/dotnet
export PATH=$PATH:/opt/dotnet
export DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1
export DOTNET_CLI_TELEMETRY_OPTOUT=1
B=/sdcard/DeltaPatch
S=$B/pc/data.win
A=$B/android/chapter3.droid
for f in "$S" "$A"; do [ -f "$f" ] || { echo "MISSING $f - use 'Export files' in the launcher first"; exit 1; }; done
rm -rf $B/dump/pc $B/dump/android
UTMT_OUT=$B/dump/pc      dotnet /opt/utmt/UndertaleModCli.dll load "$S" -s $B/termux/ExportCode.csx
UTMT_OUT=$B/dump/android dotnet /opt/utmt/UndertaleModCli.dll load "$A" -s $B/termux/ExportCode.csx
cd $B/dump
diff -ru pc android > hadrian_edits.diff || true
ls -la hadrian_edits.diff
grep -c '^diff ' hadrian_edits.diff || true
