#!/bin/bash
set -e

# Ultra-Low Latency 60 FPS Android Mirroring with Native Metal Acceleration & Universal Clipboard

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Find adb binary
if [ -n "$ADB_BIN" ] && [ -x "$ADB_BIN" ]; then
  ADB="$ADB_BIN"
elif command -v adb > /dev/null 2>&1; then
  ADB="$(command -v adb)"
elif [ -x "/opt/homebrew/share/android-commandlinetools/platform-tools/adb" ]; then
  ADB="/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
  ADB="$HOME/Library/Android/sdk/platform-tools/adb"
elif [ -x "/opt/homebrew/bin/adb" ]; then
  ADB="/opt/homebrew/bin/adb"
elif [ -x "/usr/local/bin/adb" ]; then
  ADB="/usr/local/bin/adb"
else
  echo "Error: adb binary not found. Please install platform-tools (brew install android-platform-tools) or add adb to PATH."
  exit 1
fi

# Find scrcpy binary
if [ -n "$SCRCPY_BIN" ] && [ -x "$SCRCPY_BIN" ]; then
  SCRCPY="$SCRCPY_BIN"
elif command -v scrcpy > /dev/null 2>&1; then
  SCRCPY="$(command -v scrcpy)"
elif [ -x "/opt/homebrew/bin/scrcpy" ]; then
  SCRCPY="/opt/homebrew/bin/scrcpy"
elif [ -x "/usr/local/bin/scrcpy" ]; then
  SCRCPY="/usr/local/bin/scrcpy"
else
  echo "Error: scrcpy binary not found. Please install scrcpy (brew install scrcpy)."
  exit 1
fi

# Determine target device
TARGET="${TARGET:-$1}"
if [ -z "$TARGET" ]; then
  # Auto-detect first connected device from adb devices
  TARGET=$("$ADB" devices | grep -E '\b(device)\b' | grep -v "List" | head -n1 | awk '{print $1}')
fi

SERIAL_OPT=()
if [ -n "$TARGET" ]; then
  "$ADB" connect "$TARGET" > /dev/null 2>&1 || true
  SERIAL_OPT=("--serial=$TARGET")
fi

# Launch 60 FPS hardware-accelerated screen mirroring
exec "$SCRCPY" \
  "${SERIAL_OPT[@]}" \
  --window-title="Android Mirroring" \
  --window-width=375 \
  --max-fps=60 \
  --video-bit-rate=12M \
  --stay-awake \
  --no-clipboard-autosync \
  --shortcut-mod=lsuper,lalt \
  "$@"
