#!/bin/bash
# Builds a complete EarHop.app (universal: Apple silicon and Intel) into the folder given as $1.
# Used by build_app.sh (install into ~/Applications) and make_dmg.sh (installer disk image).
set -e
cd "$(dirname "$0")"
OUT="${1:?usage: package.sh <output folder>}"
VERSION="$(cat VERSION)"
BUILD="$(date +%Y%m%d%H%M)"

swift build -c release --arch arm64 --arch x86_64 2>&1 | grep -v "x86_64 architecture is deprecated" | tail -1
BIN="$(swift build -c release --arch arm64 --arch x86_64 --show-bin-path)/EarHop"

APP="$OUT/EarHop.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/EarHop"
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
PL="$APP/Contents/Info.plist"
cp Sources/EarHop/Info.plist "$PL"
pb() { /usr/libexec/PlistBuddy -c "$1" "$PL" >/dev/null 2>&1 || /usr/libexec/PlistBuddy -c "$2" "$PL"; }
pb "Set :CFBundleExecutable EarHop" "Add :CFBundleExecutable string EarHop"
pb "Set :CFBundlePackageType APPL" "Add :CFBundlePackageType string APPL"
pb "Set :CFBundleDisplayName EarHop" "Add :CFBundleDisplayName string EarHop"
pb "Set :CFBundleIconFile AppIcon" "Add :CFBundleIconFile string AppIcon"
pb "Set :CFBundleShortVersionString $VERSION" "Add :CFBundleShortVersionString string $VERSION"
pb "Set :CFBundleVersion $BUILD" "Add :CFBundleVersion string $BUILD"
pb "Set :LSMinimumSystemVersion 13.0" "Add :LSMinimumSystemVersion string 13.0"
pb "Set :LSApplicationCategoryType public.app-category.utilities" "Add :LSApplicationCategoryType string public.app-category.utilities"
pb "Set :NSHumanReadableCopyright EarHop, local earbud switching" "Add :NSHumanReadableCopyright string EarHop, local earbud switching"

# A stable self-signed identity keeps macOS permissions (Bluetooth, Local Network, Accessibility) across updates.
ID="EarHop Local Signing"
if ! security find-identity -p codesigning | grep -q "$ID"; then
  T="$(mktemp -d)"
  cat > "$T/c.cnf" <<CNF
[req]
distinguished_name=dn
x509_extensions=ext
prompt=no
[dn]
CN=$ID
[ext]
keyUsage=critical,digitalSignature
extendedKeyUsage=critical,codeSigning
CNF
  if openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -keyout "$T/k.pem" -out "$T/c.pem" -config "$T/c.cnf" 2>/dev/null \
     && openssl pkcs12 -export -inkey "$T/k.pem" -in "$T/c.pem" -out "$T/id.p12" -passout pass:earhop 2>/dev/null \
     && security import "$T/id.p12" -k "$HOME/Library/Keychains/login.keychain-db" -P earhop -T /usr/bin/codesign >/dev/null 2>&1; then
    echo "Created signing identity '$ID'"
  fi
  rm -rf "$T"
fi
if security find-identity -p codesigning | grep -q "$ID"; then
  codesign --force --sign "$ID" --identifier com.earhop.mac "$APP"
else
  echo "Using ad hoc signing (permissions will be asked again after every update)"
  codesign --force --sign - --identifier com.earhop.mac "$APP"
fi
codesign --verify --strict "$APP"
echo "Built $APP (version $VERSION, build $BUILD)"
