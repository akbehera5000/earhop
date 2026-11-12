#!/usr/bin/env bash
# Builds EarHop for Windows on the Mac (or any OS with the .NET 8 SDK): runs the self test, then publishes one
# self-contained dist/EarHop-Windows-<version>.exe (no .NET install needed on the PC).
# .NET 8 SDK: Microsoft's dotnet-install.sh --channel 8.0 into ~/.dotnet (the Homebrew build cannot target Windows).
set -euo pipefail
cd "$(dirname "$0")"
if [ -x "$HOME/.dotnet/dotnet" ]; then export DOTNET_ROOT="$HOME/.dotnet" PATH="$HOME/.dotnet:$PATH"; fi
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
VERSION=$(cat ../mac/VERSION 2>/dev/null || echo 1.4.0)

dotnet run --project EarHop.Tests -c Release
dotnet publish EarHop -c Release -p:Version="$VERSION" -o build/publish 2>&1 | grep -E "error|warning CS|->" || true
mkdir -p ../dist
cp build/publish/EarHop.exe "../dist/EarHop-Windows-$VERSION.exe"
echo "Built $(cd ../dist && pwd)/EarHop-Windows-$VERSION.exe ($(du -h "../dist/EarHop-Windows-$VERSION.exe" | cut -f1))"
