#!/usr/bin/env bash
set -euo pipefail

SOURCE="android/x11server/library/src/main/java/au/com/darkside/xserver/XServer.java"
if [[ ! -f "$SOURCE" ]]; then
  echo "Embedded X11 source is missing. Initialize submodules recursively." >&2
  exit 1
fi

# The upstream demo listens on every interface. DistroBoxer must expose X11
# only to its own PRoot process through IPv4 loopback.
if grep -Fq 'new ServerSocket(port);' "$SOURCE"; then
  grep -Fq 'import java.net.InetAddress;' "$SOURCE"
  sed -i 's/new ServerSocket(port);/new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));/' "$SOURCE"
fi

grep -Fq 'new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));' "$SOURCE"
echo "Embedded X11 listener is restricted to 127.0.0.1."
