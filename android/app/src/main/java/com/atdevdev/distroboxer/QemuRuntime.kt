package com.atdevdev.distroboxer

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * QEMU System runtime for app-private Linux virtual machines.
 *
 * QEMU executables and their non-platform shared libraries are packaged as
 * Android assets, then extracted into app-private executable storage. This
 * avoids loading them as JNI libraries and preserves their SONAME filenames.
 * PRoot remains available until physical-device validation is complete.
 */
class QemuRuntime(context: Context) {
    private val app = context.applicationContext
    private val vmRoot = File(app.filesDir, "qemu/vms").canonicalFile
    private val runtimeAssetPath = "qemu/arm64-v8a"
    private val runtimeRoot = File(app.filesDir, "qemu/runtime/arm64-v8a").canonicalFile
    private var activeProcess: Process? = null

    enum class GuestArchitecture(val qemuName: String, val machine: String, val serialConsole: String) {
        AARCH64("aarch64", "virt", "ttyAMA0"),
        X86_64("x86_64", "q35", "ttyS0"),
    }

    data class VmFiles(
        val directory: File,
        val disk: File,
        val kernel: File,
        val initrd: File,
    )

    /** True only when both the system emulator and disk utility are executable. */
    fun isRuntimeAvailable(architecture: GuestArchitecture = GuestArchitecture.AARCH64): Boolean {
        if (architecture != GuestArchitecture.AARCH64) return false
        val installed = isExecutableInRuntime(emulatorFor(architecture)) &&
            isExecutableInRuntime(File(runtimeRoot, "qemu-img"))
        if (installed) return true
        val assets = app.assets.list(runtimeAssetPath)?.toSet().orEmpty()
        return "qemu-system-aarch64" in assets && "qemu-img" in assets
    }

    /**
     * Imports a user-selected kernel/initrd into the private VM directory.
     * Only the two fixed filenames are accepted; arbitrary paths are rejected.
     */
    fun importBootFile(vmId: String, source: Uri, destinationName: String): File {
        require(destinationName == "kernel.img" || destinationName == "initrd.img") {
            "Solo se permiten kernel.img e initrd.img."
        }
        val directory = vmDirectory(vmId)
        check(directory.exists() || directory.mkdirs()) { "No se pudo crear el directorio de la VM." }
        val destination = File(directory, destinationName)
        require(destination.canonicalFile.parentFile == directory) { "Ruta de arranque no válida." }
        val temporary = File(directory, ".$destinationName.part")
        try {
            val input = app.contentResolver.openInputStream(source)
                ?: error("Android no pudo abrir el archivo seleccionado.")
            input.use { inputStream ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = inputStream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BOOT_FILE_BYTES) {
                            "El archivo de arranque supera el límite de 512 MiB."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(temporary.length() > 0L) { "El archivo de arranque está vacío." }
            if (destination.exists()) check(destination.delete()) { "No se pudo reemplazar el archivo anterior." }
            check(temporary.renameTo(destination)) { "No se pudo guardar el archivo de arranque." }
        } finally {
            temporary.delete()
        }
        return destination
    }

    fun hasBootInputs(vmId: String): Boolean {
        val directory = runCatching { vmDirectory(vmId) }.getOrNull() ?: return false
        val kernel = File(directory, "kernel.img")
        val initrd = File(directory, "initrd.img")
        return isPrivateRegularFile(directory, kernel) && kernel.length() > 0L &&
            isPrivateRegularFile(directory, initrd) && initrd.length() > 0L
    }

    fun hasDisk(vmId: String): Boolean {
        val directory = runCatching { vmDirectory(vmId) }.getOrNull() ?: return false
        val disk = File(directory, "system.qcow2")
        return isPrivateRegularFile(directory, disk) && disk.length() > 0L
    }

    /** Creates a sparse QCOW2 disk; virtual capacity is limited to 4–64 GiB. */
    fun createDisk(vmId: String, sizeGiB: Int = 12): File {
        require(sizeGiB in 4..64) { "El disco debe tener entre 4 y 64 GiB." }
        check(isRuntimeAvailable()) { "QEMU y qemu-img todavía no están empaquetados en esta versión." }
        ensureRuntimeInstalled()
        val directory = vmDirectory(vmId)
        check(directory.exists() || directory.mkdirs()) { "No se pudo crear el directorio de la VM." }
        val disk = File(directory, "system.qcow2")
        require(disk.canonicalFile.parentFile == directory) { "Ruta del disco no válida." }
        if (disk.exists()) return disk
        val process = ProcessBuilder(
            File(runtimeRoot, "qemu-img").absolutePath,
            "create", "-f", "qcow2", disk.absolutePath, "${sizeGiB}G",
        ).directory(directory).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = runtimeRoot.absolutePath
        }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0 && disk.isFile && disk.length() > 0L) {
            "qemu-img no pudo crear el disco (código $exit): ${output.takeLast(1200)}"
        }
        return disk
    }

    /**
     * Boots a VM from a matching Linux kernel + initrd and an existing QCOW2
     * disk. Console I/O is attached to Process stdin/stdout for TerminalSession.
     * TCG is explicit: KVM is never assumed on unrooted Android.
     */
    @Synchronized
    fun start(
        vmId: String,
        architecture: GuestArchitecture = GuestArchitecture.AARCH64,
        memoryMiB: Int = 1024,
        vcpus: Int = 2,
        enableVnc: Boolean = false,
    ): Process {
        require(activeProcess?.isAlive != true) { "Ya existe una máquina virtual activa." }
        require(memoryMiB in 512..4096) { "La memoria debe estar entre 512 y 4096 MiB." }
        require(vcpus in 1..4) { "La VM admite entre 1 y 4 vCPU en esta configuración." }
        check(isRuntimeAvailable(architecture)) {
            "El runtime QEMU para ${architecture.qemuName} no está incluido en esta APK."
        }
        ensureRuntimeInstalled()

        val directory = vmDirectory(vmId)
        val disk = File(directory, "system.qcow2")
        val kernel = File(directory, "kernel.img")
        val initrd = File(directory, "initrd.img")
        check(disk.isFile && disk.length() > 0L) { "Primero crea el disco QCOW2 de la máquina." }
        check(isPrivateRegularFile(directory, kernel) && kernel.length() > 0L) {
            "Falta kernel.img. Importa un kernel Linux compatible."
        }
        check(isPrivateRegularFile(directory, initrd) && initrd.length() > 0L) {
            "Falta initrd.img. Importa un initramfs compatible con el disco virtio."
        }

        val blockDevice = if (architecture == GuestArchitecture.AARCH64) "virtio-blk-device" else "virtio-blk-pci"
        val networkDevice = if (architecture == GuestArchitecture.AARCH64) "virtio-net-device" else "virtio-net-pci"
        val command = mutableListOf(
            emulatorFor(architecture).absolutePath,
            "-machine", architecture.machine,
            "-accel", "tcg,thread=multi",
            "-cpu", "max",
            "-smp", vcpus.toString(),
            "-m", memoryMiB.toString(),
            "-nodefaults",
            "-display", "none",
            "-monitor", "none",
            "-serial", "stdio",
            "-kernel", kernel.absolutePath,
            "-initrd", initrd.absolutePath,
            "-append", "console=${architecture.serialConsole} root=/dev/vda rw",
            "-drive", "if=none,file=${disk.absolutePath},format=qcow2,id=systemdisk",
            "-device", "$blockDevice,drive=systemdisk",
            "-netdev", "user,id=net0",
            "-device", "$networkDevice,netdev=net0",
        )
        if (enableVnc) {
            // VNC is intentionally loopback-only; never expose an unauthenticated
            // display server to the local Wi-Fi network.
            command += listOf("-vnc", "127.0.0.1:1")
        }

        val process = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = runtimeRoot.absolutePath }
            .start()
        activeProcess = process
        return process
    }

    @Synchronized
    fun stop() {
        activeProcess?.let { process ->
            if (process.isAlive) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        }
        activeProcess = null
    }

    @Synchronized
    fun isRunning(): Boolean = activeProcess?.isAlive == true

    private fun emulatorFor(architecture: GuestArchitecture): File =
        File(runtimeRoot, "qemu-system-${architecture.qemuName}")

    /**
     * Installs the signed-by-APK runtime assets into app-private storage.
     * Called from disk/VM operations that already run on Dispatchers.IO.
     */
    @Synchronized
    private fun ensureRuntimeInstalled() {
        val assetNames = app.assets.list(runtimeAssetPath)?.toSet().orEmpty()
        check("qemu-system-aarch64" in assetNames && "qemu-img" in assetNames &&
            "SHA256SUMS" in assetNames && "BUILD-INFO.txt" in assetNames
        ) { "Esta APK no incluye el runtime QEMU ARM64 completo." }

        val checksumText = app.assets.open("$runtimeAssetPath/SHA256SUMS")
            .bufferedReader().use { it.readText() }
        val expectedHashes = checksumText.lineSequence().mapNotNull { line ->
            val match = Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+)$").matchEntire(line.trim())
                ?: return@mapNotNull null
            match.groupValues[2] to match.groupValues[1].lowercase()
        }.toMap()
        check("qemu-system-aarch64" in expectedHashes && "qemu-img" in expectedHashes) {
            "El manifiesto SHA-256 no incluye los ejecutables QEMU."
        }

        val buildInfo = app.assets.open("$runtimeAssetPath/BUILD-INFO.txt")
            .bufferedReader().use { it.readText() }
        val version = "${buildInfo.hashCode()}-${checksumText.hashCode()}"
        val marker = File(runtimeRoot, ".runtime-version")
        val installedFilesValid = expectedHashes.all { (name, expected) ->
            val installed = File(runtimeRoot, name)
            installed.isFile && installed.canonicalFile.parentFile == runtimeRoot &&
                sha256(installed) == expected
        }
        if (marker.isFile && marker.readText() == version && installedFilesValid &&
            isExecutableInRuntime(emulatorFor(GuestArchitecture.AARCH64)) &&
            isExecutableInRuntime(File(runtimeRoot, "qemu-img"))
        ) return

        check(runtimeRoot.exists() || runtimeRoot.mkdirs()) {
            "No se pudo crear el directorio privado del runtime QEMU."
        }
        for (name in assetNames) {
            require(name.matches(Regex("[A-Za-z0-9_.+-]+"))) { "Nombre de archivo QEMU no válido." }
            val destination = File(runtimeRoot, name).canonicalFile
            require(destination.parentFile == runtimeRoot) { "Ruta de runtime QEMU no válida." }
            val temporary = File(runtimeRoot, ".$name.part")
            try {
                app.assets.open("$runtimeAssetPath/$name").use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                val expected = expectedHashes[name]
                val isBinary = name == "qemu-system-aarch64" || name == "qemu-img" || name.contains(".so")
                if (isBinary) {
                    check(expected != null && sha256(temporary) == expected) {
                        "La verificación SHA-256 falló para el archivo QEMU: $name"
                    }
                }
                check(temporary.renameTo(destination) || destination.delete() && temporary.renameTo(destination)) {
                    "No se pudo instalar el archivo QEMU: $name"
                }
                val executable = name == "qemu-system-aarch64" || name == "qemu-img"
                check(destination.setReadable(true, true)) { "No se pudo habilitar lectura para $name." }
                if (executable) {
                    check(destination.setExecutable(true, true)) { "No se pudo habilitar ejecución para $name." }
                }
            } finally {
                temporary.delete()
            }
        }
        marker.writeText(version)
        check(installedFilesValidAfterInstall(expectedHashes) &&
            isExecutableInRuntime(emulatorFor(GuestArchitecture.AARCH64)) &&
            isExecutableInRuntime(File(runtimeRoot, "qemu-img"))
        ) { "La instalación del runtime QEMU quedó incompleta o dañada." }
    }

    private fun installedFilesValidAfterInstall(expectedHashes: Map<String, String>): Boolean =
        expectedHashes.all { (name, expected) ->
            val installed = File(runtimeRoot, name)
            installed.isFile && installed.canonicalFile.parentFile == runtimeRoot &&
                sha256(installed) == expected
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun isExecutableInRuntime(file: File): Boolean =
        file.isFile && file.canExecute() && file.canonicalFile.parentFile == runtimeRoot

    private fun vmDirectory(vmId: String): File {
        require(vmId.matches(Regex("[a-z][a-z0-9_-]{1,31}"))) { "Identificador de VM no válido." }
        check(vmRoot.exists() || vmRoot.mkdirs()) { "No se pudo crear el almacenamiento QEMU." }
        val directory = File(vmRoot, vmId).canonicalFile
        require(directory.parentFile == vmRoot && directory.path.startsWith(vmRoot.path + File.separator)) {
            "La VM intenta salir del almacenamiento privado."
        }
        return directory
    }

    private fun isPrivateRegularFile(directory: File, file: File): Boolean =
        file.isFile && file.canonicalFile.parentFile == directory.canonicalFile

    companion object {
        private const val MAX_BOOT_FILE_BYTES = 512L * 1024L * 1024L
    }
}
