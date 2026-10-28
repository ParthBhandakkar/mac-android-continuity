#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# 1. Build Aether App
HUB_APP="Aether"
HUB_BUNDLE="$DIR/$HUB_APP.app"

echo "[1/4] Building Aether.app..."
rm -rf "$HUB_BUNDLE"
mkdir -p "$HUB_BUNDLE/Contents/MacOS"
mkdir -p "$HUB_BUNDLE/Contents/Resources"

swiftc -O -target arm64-apple-macosx14.0 -framework Cocoa -framework WebKit -o "$HUB_BUNDLE/Contents/MacOS/$HUB_APP" ContinuityHub.swift
cp "$DIR/hub_ui.html" "$HUB_BUNDLE/Contents/Resources/hub_ui.html"
if [ -f "$DIR/qrcode.min.js" ]; then
  cp "$DIR/qrcode.min.js" "$HUB_BUNDLE/Contents/Resources/qrcode.min.js"
fi

cat << 'EOF' > "$HUB_BUNDLE/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>en</string>
	<key>CFBundleDisplayName</key>
	<string>Aether</string>
	<key>CFBundleExecutable</key>
	<string>Aether</string>
	<key>CFBundleIconFile</key>
	<string>AppIcon</string>
	<key>CFBundleIdentifier</key>
	<string>com.workzera.aether</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>Aether</string>
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

if [ -f "$DIR/AppIcon.icns" ]; then
  cp "$DIR/AppIcon.icns" "$HUB_BUNDLE/Contents/Resources/AppIcon.icns"
fi

rm -rf "/Applications/Aether.app"
mv "$HUB_BUNDLE" "/Applications/Aether.app"
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -r /Applications/Aether.app

echo "SUCCESS: Native macOS App installed cleanly to /Applications/Aether.app"
ls -ld "/Applications/Aether.app"
