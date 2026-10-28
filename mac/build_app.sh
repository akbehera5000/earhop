#!/bin/bash
# Builds EarHop.app and installs it into /Applications (the same place the DMG installs to).
set -e
cd "$(dirname "$0")"
bash package.sh /Applications
# Restart a running EarHop so the new build is the one in use (installing alone leaves the old one running).
if pgrep -f "/Applications/EarHop.app/Contents/MacOS/EarHop" >/dev/null; then
  pkill -f "/Applications/EarHop.app/Contents/MacOS/EarHop" || true
  sleep 1
  open /Applications/EarHop.app
  echo "Restarted EarHop"
fi
