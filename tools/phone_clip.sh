#!/bin/bash
# Starts the EarHop clipboard helper on an Android phone connected with adb (USB or wireless debugging).
# The helper runs as the shell user, which Android lets read the clipboard in the background, and tells the
# EarHop app about every copy. Run it again after the phone restarts or after installing a new EarHop.
set -e
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
APK=$("$ADB" shell pm path com.earhop.logger | head -1 | cut -d: -f2 | tr -d '\r')
if [ -z "$APK" ]; then echo "EarHop is not installed on the phone"; exit 1; fi
"$ADB" shell "kill \$(pidof earhop-clip) 2>/dev/null; sleep 0.5; CLASSPATH=$APK setsid nohup app_process /system/bin --nice-name=earhop-clip com.earhop.logger.ClipHelper >/data/local/tmp/earhop-clip.log 2>&1 < /dev/null &"
sleep 1
if "$ADB" shell pidof earhop-clip >/dev/null; then echo "EarHop clipboard helper is running"; else echo "The helper did not start"; exit 1; fi
