package com.atdevdev.distroboxer

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes a validated Linux rootfs with the ABI-matched PRoot binary.
 *
 * PRoot runs inside the Android app sandbox. The -0 option only emulates
 * guest UID/GID 0; it does not grant Android root privileges.
 */
class ProotRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val filesRoot = appContext.filesDir.canonicalFile
    private val prootFile = File(appContext.applicationInfo.nativeLibraryDir, "libproot.so")
    private var activeProcess: Process? = null

    @Synchronized
    fun isRuntimeAvailable(): Boolean = runCatching {
        if (!isSafeRegularFile(prootFile)) return@runCatching false
        val mode = Os.stat(prootFile.absolutePath).st_mode
        val executeMask = OsConstants.S_IXUSR or OsConstants.S_IXGRP or OsConstants.S_IXOTH
        (mode and executeMask) != 0 && prootFile.canExecute()
    }.getOrDefault(false)

    fun runtimeDiagnostics(): String = runCatching {
        val stat = Os.stat(prootFile.absolutePath)
        "path=${prootFile.absolutePath}, exists=${prootFile.exists()}, " +
            "size=${prootFile.length()}, mode=${Integer.toOctalString(stat.st_mode and 0x1FF)}, " +
            "uid=${stat.st_uid}, gid=${stat.st_gid}"
    }.getOrElse { error ->
        "path=${prootFile.absolutePath}, exists=${prootFile.exists()}, " +
            "nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}, " +
            "diagnostic=${error.message ?: error.javaClass.simpleName}"
    }

    @Synchronized
    fun startShell(distroId: String): Process {
        check(activeProcess?.isAlive != true) { "Ya existe una sesión Linux activa." }
        check(isRuntimeAvailable()) {
            "PRoot no está disponible o no tiene permiso de ejecución. ${runtimeDiagnostics()}"
        }

        val rootfs = resolveRootfs(distroId)
        val shellPath = if (File(rootfs, "bin/sh").exists()) "/bin/sh" else "/usr/bin/sh"
        ensureGuestExecutable(rootfs, shellPath.removePrefix("/"))
        ensureGuestDirectory(rootfs, "root", 0x1C0) // 0700
        ensureGuestDirectory(rootfs, "tmp", 0x3FF) // 01777
        val prootTempDirectory = prepareProotTempDirectory()

        val command = mutableListOf(
            prootFile.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-w", "/root",
        )

        // Only expose the Linux pseudo-filesystems that Android permits the app to read.
        // Android's /system and /apex are deliberately not mounted into the guest.
        listOf("/dev", "/proc", "/sys").forEach { hostPath ->
            val hostDirectory = File(hostPath)
            if (hostDirectory.isDirectory && hostDirectory.canRead()) {
                command += listOf("-b", hostPath)
            }
        }
        command += listOf(shellPath, "-i")

        val process = ProcessBuilder(command)
            .directory(appContext.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment()["HOME"] = "/root"
                environment()["USER"] = "root"
                environment()["LOGNAME"] = "root"
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["LANG"] = "C.UTF-8"
                environment()["TERM"] = "xterm-256color"
                environment()["TMPDIR"] = "/tmp"
                environment()["PROOT_TMP_DIR"] = prootTempDirectory.absolutePath
            }
            .start()

        // ProcessBuilder can start successfully even when ptrace or guest exec fails immediately.
        if (process.waitFor(600, TimeUnit.MILLISECONDS)) {
            val startupOutput = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            throw IllegalStateException(
                "PRoot terminó durante el arranque (código ${process.exitValue()}). " +
                    startupOutput.trim().ifEmpty { runtimeDiagnostics() },
            )
        }

        activeProcess = process
        return process
    }

    @Synchronized
    fun stop() {
        activeProcess?.let { process ->
            if (process.isAlive) process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
        activeProcess = null
    }

    @Synchronized
    fun isRunning(): Boolean = activeProcess?.isAlive == true

    fun isDistroInstalled(distroId: String): Boolean = runCatching {
        resolveRootfs(distroId)
    }.isSuccess

    private fun resolveRootfs(distroId: String): File {
        require(distroId.matches(Regex("[a-z][a-z0-9_-]{1,31}"))) {
            "Identificador de distribución no válido."
        }

        val distrosDirectory = File(filesRoot, "distros")
        val canonicalDistros = distrosDirectory.canonicalFile
        require(canonicalDistros == distrosDirectory.absoluteFile) {
            "Directorio de distribuciones no válido."
        }

        val distroDirectory = File(canonicalDistros, distroId).canonicalFile
        require(distroDirectory.parentFile == canonicalDistros) {
            "La ruta de la distribución sale del almacenamiento privado."
        }

        val rootfs = File(distroDirectory, "rootfs").canonicalFile
        require(rootfs.parentFile == distroDirectory && isSafeDirectory(rootfs)) {
            "No existe un rootfs Linux válido para $distroId."
        }

        val releaseFile = File(rootfs, "etc/os-release").canonicalFile
        require(releaseFile.toPath().startsWith(rootfs.toPath()) && releaseFile.isFile) {
            "La distribución no contiene un etc/os-release válido dentro de rootfs."
        }

        val shell = listOf(File(rootfs, "bin/sh"), File(rootfs, "usr/bin/sh"))
            .firstOrNull { it.exists() }
        require(shell != null) { "La distribución no contiene /bin/sh ni /usr/bin/sh." }

        return rootfs
    }

    /** Repairs only the guest shell and parent traversal bits after validating canonical paths. */
    private fun ensureGuestExecutable(rootfs: File, relativePath: String) {
        val root = rootfs.canonicalFile
        val target = File(root, relativePath).canonicalFile
        check(target.toPath().startsWith(root.toPath()) && target.isFile) {
            "El ejecutable Linux /$relativePath no existe o apunta fuera del rootfs."
        }

        val directories = mutableListOf<File>()
        var parent: File? = target.parentFile
        while (parent != null && parent.toPath().startsWith(root.toPath())) {
            directories += parent
            if (parent == root) break
            parent = parent.parentFile
        }
        check(directories.lastOrNull() == root) {
            "No se pudo validar la ruta completa del ejecutable /$relativePath."
        }

        directories.asReversed().forEach { directory ->
            val mode = Os.stat(directory.absolutePath).st_mode and 0x1FF
            Os.chmod(directory.absolutePath, mode or 0x1ED) // ensure 0755 traversal
        }
        val fileMode = Os.stat(target.absolutePath).st_mode and 0x1FF
        Os.chmod(target.absolutePath, fileMode or 0x1ED)

        check(target.canRead() && target.canExecute() &&
            directories.all { it.canRead() && it.canExecute() }) {
            "El shell /$relativePath o alguno de sus directorios no tiene permisos de ejecución."
        }
    }

    /** Creates safe guest directories; symlinks must resolve inside this rootfs. */
    private fun ensureGuestDirectory(rootfs: File, relativePath: String, mode: Int) {
        val root = rootfs.canonicalFile
        val entry = File(root, relativePath)
        if (!entry.exists()) {
            check(entry.mkdirs()) { "No se pudo crear /$relativePath dentro del rootfs." }
        }
        val target = entry.canonicalFile
        check(target.toPath().startsWith(root.toPath()) && target.isDirectory) {
            "La ruta /$relativePath no es un directorio seguro dentro del rootfs."
        }
        Os.chmod(target.absolutePath, mode)
        check(target.canRead() && target.canWrite() && target.canExecute()) {
            "El directorio /$relativePath no tiene permisos suficientes."
        }
    }

    /** PRoot's host-side temporary files stay in the app-private cache directory. */
    private fun prepareProotTempDirectory(): File {
        val cacheRoot = appContext.cacheDir.canonicalFile
        val tempDirectory = File(cacheRoot, "proot-tmp")
        if (!tempDirectory.exists()) {
            check(tempDirectory.mkdirs()) { "No se pudo crear el directorio temporal de PRoot." }
        }

        val canonicalTemp = tempDirectory.canonicalFile
        check(canonicalTemp.parentFile == cacheRoot && canonicalTemp.isDirectory && canonicalTemp.canWrite()) {
            "El directorio temporal de PRoot no es válido o no permite escritura."
        }

        val probe = File.createTempFile(".proot-check-", ".tmp", canonicalTemp)
        try {
            check(probe.isFile && probe.canWrite()) { "No se puede escribir en el directorio temporal de PRoot." }
        } finally {
            check(!probe.exists() || probe.delete()) { "No se pudo limpiar el archivo temporal de prueba." }
        }
        return canonicalTemp
    }

    private fun isSafeDirectory(file: File): Boolean =
        file.exists() && file.isDirectory && file.canonicalFile == file.absoluteFile

    private fun isSafeRegularFile(file: File): Boolean =
        file.exists() && file.isFile && file.canonicalFile == file.absoluteFile
}
