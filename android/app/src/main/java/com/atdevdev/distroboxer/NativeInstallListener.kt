package com.atdevdev.distroboxer

/** Receives live native rootfs installation messages from the Rust extractor. */
interface NativeInstallListener {
    fun onInstallLog(message: String)
}
