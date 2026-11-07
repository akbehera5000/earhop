#!/bin/bash
# Runs every automated test: the Python protocol reference, the Mac self test (protocol, Buds frames, handoff
# simulator) and the Android unit tests. Exit code is non-zero if any fail.
set -e
set -o pipefail
cd "$(dirname "$0")/.."
python3 tools/earhop_tool.py selftest
# The last line must be exactly "selftest OK" (a crash or a failed check stops the run).
MAC_RESULT="$(cd mac && swift build -c release >/dev/null && EARHOP_NO_BT=1 "$(swift build -c release --show-bin-path)/EarHop" --selftest 2>&1 | tail -1)"
echo "$MAC_RESULT"
[ "$MAC_RESULT" = "selftest OK" ] || { echo "Mac self test failed"; exit 1; }
if [ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [ -n "$JAVA_HOME" ]; then
    (cd android && ./gradlew -q testDebugUnitTest && echo "android unit tests OK")
else
    echo "android unit tests skipped: no JDK 17 found"
fi
