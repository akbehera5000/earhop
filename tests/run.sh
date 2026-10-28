#!/bin/bash
# Runs every automated test: the Python protocol reference, the Mac self test (protocol, Buds frames, handoff
# simulator) and the Android unit tests. Exit code is non-zero if any fail.
set -e
cd "$(dirname "$0")/.."
python3 tools/earhop_tool.py selftest
(cd mac && swift build -c release >/dev/null && EARHOP_NO_BT=1 "$(swift build -c release --show-bin-path)/EarHop" --selftest | tail -1)
if [ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [ -n "$JAVA_HOME" ]; then
    (cd android && ./gradlew -q testDebugUnitTest && echo "android unit tests OK")
else
    echo "android unit tests skipped: no JDK 17 found"
fi
