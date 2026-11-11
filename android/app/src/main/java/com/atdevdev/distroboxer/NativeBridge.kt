package com.atdevdev.distroboxer

/**
 * JNI bridge to the DistroBoxer Rust engine.
 *
 * The Android build packages libdistroboxer.so for supported ABIs.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("distroboxer")
    }

    external fun nativeSupportedDistros(): String
}
