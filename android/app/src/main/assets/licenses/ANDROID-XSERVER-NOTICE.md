# Embedded Android X11 server

DistroBoxer includes the Java X11 server library from [nwrkbiz/android-xserver](https://github.com/nwrkbiz/android-xserver), pinned as a Git submodule to commit `51e907880d4295ee3c381a593c738ca365c066fb`.

- Original author: Matt Kwan
- Android fork maintainer: Daniel Giritzer
- License: MIT; see `ANDROID-XSERVER-MIT.txt`.
- DistroBoxer build patch: bind the server socket only to IPv4 loopback (`127.0.0.1`) so X11 is not exposed to the local network.

This is a lightweight Android-embedded X11 implementation. It provides a real X11 protocol server and Android screen view, but it is not full X.Org and does not promise GLX, GPU acceleration, or compatibility with every modern desktop environment. XFCE and other desktop sessions must be verified on physical devices before being advertised as supported.
