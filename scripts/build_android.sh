#!/usr/bin/env sh
# Build the Madar Chart APK: web UI -> Capacitor sync -> Gradle -> ~/Desktop/MadarChart.apk
#   sh scripts/build_android.sh          build
#   sh scripts/build_android.sh test     run the Kotlin unit tests first
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sh scripts/sync_web.sh
(cd android-app && npx cap sync android)
cd android-app/android
if [ "$1" = "test" ]; then ./gradlew testDebugUnitTest; fi
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk "$HOME/Desktop/MadarChart.apk"
echo "APK ready: $HOME/Desktop/MadarChart.apk ($(du -h "$HOME/Desktop/MadarChart.apk" | cut -f1))"
