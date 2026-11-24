#!/usr/bin/env sh
# Copy the web UI (hub/*.html, hub/*.js) into the Android app's web folder. No Python, logs, caches or tests.
set -e
cd "$(dirname "$0")/.."
rm -rf android-app/www
mkdir -p android-app/www
cp hub/*.html hub/*.js android-app/www/
echo "web assets: $(ls android-app/www | wc -l | tr -d ' ') files, $(du -sh android-app/www | cut -f1)"
