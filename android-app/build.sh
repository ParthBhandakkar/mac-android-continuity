#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# Auto-detect Android SDK
if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ]; then
  SDK_ROOT="$ANDROID_HOME"
elif [ -n "$ANDROID_SDK_ROOT" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
  SDK_ROOT="$ANDROID_SDK_ROOT"
elif [ -d "$HOME/Library/Android/sdk" ]; then
  SDK_ROOT="$HOME/Library/Android/sdk"
elif [ -d "/usr/local/share/android-sdk" ]; then
  SDK_ROOT="/usr/local/share/android-sdk"
else
  echo "Error: Android SDK not found. Please set ANDROID_HOME."
  exit 1
fi

# Locate latest build-tools and platform jar
BUILD_TOOLS=$(find "$SDK_ROOT/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)
PLATFORM=$(find "$SDK_ROOT/platforms" -name "android.jar" | sort -V | tail -n 1)

# Locate Java compiler and keytool
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"
JAVAC="${JAVAC:-$(which javac)}"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/keytool}"
KEYTOOL="${KEYTOOL:-$(which keytool)}"

rm -rf build && mkdir -p build/gen build/obj build/apk

echo "[1/6] Compiling resources with aapt2..."
"$BUILD_TOOLS/aapt2" compile --dir res -o build/resources.zip
"$BUILD_TOOLS/aapt2" link build/resources.zip \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  -o build/apk/app.unsigned.apk \
  --auto-add-overlay

echo "[2/6] Compiling Java sources..."
"$JAVAC" -d build/obj \
  -classpath "$PLATFORM:build/gen" \
  -source 1.8 -target 1.8 \
  src/com/workzera/continuity/*.java build/gen/com/workzera/continuity/R.java

echo "[3/6] Converting bytecode to Dalvik Executable (classes.dex) with d8..."
find build/obj -name "*.class" > build/classes.txt
"$BUILD_TOOLS/d8" $(cat build/classes.txt) --output build/apk/ --lib "$PLATFORM"

echo "[4/6] Packaging APK..."
cd build/apk
jar uf app.unsigned.apk classes.dex
cd "$DIR"

echo "[5/6] Aligning APK with zipalign..."
"$BUILD_TOOLS/zipalign" -v -p 4 build/apk/app.unsigned.apk build/apk/app.aligned.apk > /dev/null

echo "[6/6] Generating debug keystore & signing APK with apksigner..."
if [ ! -f "$DIR/debug.keystore" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$DIR/debug.keystore" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
fi

"$BUILD_TOOLS/apksigner" sign --ks "$DIR/debug.keystore" --ks-pass pass:android --key-pass pass:android --out ContinuityCompanion.apk build/apk/app.aligned.apk

echo "SUCCESS: ContinuityCompanion.apk generated successfully!"
ls -lh ContinuityCompanion.apk
