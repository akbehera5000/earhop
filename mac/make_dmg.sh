#!/bin/bash
# Builds the installer disk image dist/EarHop-<version>.dmg: EarHop.app, a link to Applications and a short guide.
# Open the image, drag EarHop onto Applications, then open EarHop from Applications.
set -e
cd "$(dirname "$0")"
VERSION="$(cat VERSION)"
DIST="$(cd .. && pwd)/dist"
DMG="$DIST/EarHop-$VERSION.dmg"
STAGE="$(mktemp -d)/EarHop"
mkdir -p "$STAGE" "$DIST"

bash package.sh "$STAGE"
ln -s /Applications "$STAGE/Applications"
cat > "$STAGE/Install EarHop.txt" <<TXT
EarHop $VERSION

1. Drag EarHop onto the Applications folder in this window.
2. Open EarHop from Applications. It lives in the menu bar (no Dock icon).
3. Allow Bluetooth and Local Network when macOS asks.
4. Connect your earbuds to this Mac once, open the EarHop menu and press Detect.
5. Allow Accessibility only if EarHop asks for it (used for the Play/Pause key fallback).

If macOS says EarHop cannot be opened: this build is signed on its own Mac, not by an Apple
Developer ID, so on any other Mac open System Settings, Privacy & Security, and press
"Open Anyway" next to EarHop once.

Updating: quit EarHop from its menu, drag the new EarHop onto Applications and replace.
Your settings stay in ~/.earhop.
TXT

rm -f "$DMG"
hdiutil create -volname "EarHop $VERSION" -srcfolder "$STAGE" -fs HFS+ -format UDZO -imagekey zlib-level=9 -ov "$DMG" >/dev/null
ID="EarHop Local Signing"
if security find-identity -p codesigning | grep -q "$ID"; then codesign --force --sign "$ID" "$DMG"; fi
hdiutil verify "$DMG" >/dev/null
rm -rf "$(dirname "$STAGE")"
echo "Built $DMG ($(du -h "$DMG" | cut -f1))"
