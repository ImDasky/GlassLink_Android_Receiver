#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
P=app/src/main/java/com/glasslink/receiver
kotlinc "$P/FramePlaybackScript.kt" "$P/WebVideoScript.kt" qa/WriteScripts.kt -include-runtime -d "$TMP/scripts.jar"
mkdir -p qa/generated_js
java -jar "$TMP/scripts.jar" qa/generated_js
for f in qa/generated_js/*.js; do node --check "$f"; done
node qa/frame_integration_test.js
rm -rf qa/generated_js
