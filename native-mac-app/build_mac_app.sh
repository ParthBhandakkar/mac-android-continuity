#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# 1. Build Continuity Hub App
HUB_APP="Continuity Hub"
HUB_BUNDLE="$DIR/$HUB_APP.app"

echo "[1/4] Building Continuity Hub.app..."
rm -rf "$HUB_BUNDLE"
mkdir -p "$HUB_BUNDLE/Contents/MacOS"
mkdir -p "$HUB_BUNDLE/Contents/Resources"

swiftc -O -target arm64-apple-macosx14.0 -framework Cocoa -framework WebKit -o "$HUB_BUNDLE/Contents/MacOS/$HUB_APP" ContinuityHub.swift
cp "$DIR/hub_ui.html" "$HUB_BUNDLE/Contents/Resources/hub_ui.html"

cat << 'EOF' > "$HUB_BUNDLE/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>en</string>
	<key>CFBundleDisplayName</key>
	<string>Continuity Hub</string>
	<key>CFBundleExecutable</key>
	<string>Continuity Hub</string>
	<key>CFBundleIconFile</key>
	<string>AppIcon</string>
	<key>CFBundleIdentifier</key>
	<string>com.workzera.ContinuityHub</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>Continuity Hub</string>
	<key>CFBundlePackageType</key>
	<string>APPL</string>
	<key>CFBundleShortVersionString</key>
	<string>1.0</string>
	<key>CFBundleVersion</key>
	<string>1.0.0</string>
	<key>LSMinimumSystemVersion</key>
	<string>14.0</string>
	<key>NSHighResolutionCapable</key>
	<true/>
</dict>
</plist>
EOF

if [ -f "/System/Applications/iPhone Mirroring.app/Contents/Resources/AppIcon.icns" ]; then
  cp "/System/Applications/iPhone Mirroring.app/Contents/Resources/AppIcon.icns" "$HUB_BUNDLE/Contents/Resources/AppIcon.icns"
fi

echo "SUCCESS: Native macOS App '$HUB_BUNDLE' built successfully!"
ls -ld "$HUB_BUNDLE"
