#!/usr/bin/env sh
# "New version": refresh data -> rebuild the pack -> build the APK -> copy to Desktop -> install on the tablet if it is plugged in.
#   sh tools/new_version.sh                         refresh feed candles, rebuild pack + APK
#   sh tools/new_version.sh --histdata FOLDER       first (re)import the HistData folder (zip/csv), then the same
#   sh tools/new_version.sh --no-refresh            skip the feed top-up (offline)
#   sh tools/new_version.sh --test                  run the Kotlin unit tests before building
set -e
cd "$(dirname "$0")/.."
PY=.venv/bin/python; [ -x "$PY" ] || PY=python3
REFRESH=1; TEST=""; HIST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --histdata) HIST="$2"; shift ;;
    --no-refresh) REFRESH=0 ;;
    --test) TEST="test" ;;
  esac; shift
done
[ -n "$HIST" ] && $PY tools/import_histdata.py "$HIST" --check-gold
[ "$REFRESH" = 1 ] && $PY tools/refresh_recent.py || true
$PY tools/build_pack.py
sh scripts/build_android.sh $TEST
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
ADB="$ANDROID_HOME/platform-tools/adb"
if [ -x "$ADB" ] && [ -n "$("$ADB" devices | sed 1d | grep -w device)" ]; then
  echo "tablet found: installing over the old app (data is kept)"
  "$ADB" install -r "$HOME/Desktop/MadarChart.apk"
else
  echo "no tablet connected by adb: copy ~/Desktop/MadarChart.apk to the tablet and install it over the old one (never uninstall first)"
fi
