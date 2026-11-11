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

    external fun nativePrepareDistro(distroId: String, baseDirectory: String): Boolean

    external fun nativeIsInstalled(distroId: String, baseDirectory: String): Boolean

    external fun nativeInstallTarGz(
        distroId: String,
        baseDirectory: String,
        archivePath: String,
        expectedSha256: String,
    ): String?
}
