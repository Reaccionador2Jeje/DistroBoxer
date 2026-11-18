package com.atdevdev.distroboxer

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Starts an installed Linux rootfs with the ABI-matched PRoot executable
 * packaged in Android's native-library directory.
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
        if ((mode and executeMask) == 0) return@runCatching false
        Os.access(prootFile.absolutePath, OsConstants.X_OK)
        true
    }.getOrDefault(false)

    /** Host-side details included in startup errors to diagnose Android EACCES. */
    fun runtimeDiagnostics(): String = runCatching {
        val stat = Os.stat(prootFile.absolutePath)
        "path=" + prootFile.absolutePath +
            ", exists=" + prootFile.exists() +
            ", size=" + prootFile.length() +
            ", mode=" + Integer.toOctalString(stat.st_mode and 0x1FF) +
            ", uid=" + stat.st_uid +
            ", gid=" + stat.st_gid
    }.getOrElse { error ->
        "path=" + prootFile.absolutePath +
            ", exists=" + prootFile.exists() +
            ", nativeLibraryDir=" + appContext.applicationInfo.nativeLibraryDir +
            ", diagnostic=" + (error.message ?: error.javaClass.simpleName)
    }

    @Synchronized
    fun installBundledRuntime(abi: String) {
        require(abi == "arm64-v8a" || abi == "x86_64") {
            "Arquitectura Android no compatible con PRoot: " + abi
        }
        check(isRuntimeAvailable()) {
            "PRoot no está ejecutable desde el directorio nativo de Android (" +
                runtimeDiagnostics() + ")."
        }
    }

    @Synchronized
    fun startShell(distroId: String, display: String? = null): Process {
        check(activeProcess?.isAlive != true) { "Ya existe una sesión Linux activa" }
        check(isRuntimeAvailable()) {
            "PRoot no está disponible: " + runtimeDiagnostics()
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

        // Bind only Linux pseudo-filesystems. Android's /system and /apex are
        // intentionally excluded: they are host internals and not guest mounts.
        listOf("/dev", "/proc", "/sys").forEach { hostPath ->
            val hostDirectory = File(hostPath)
            if (hostDirectory.isDirectory && hostDirectory.canRead()) {
                command += listOf("-b", hostPath)
            }
        }
        command += listOf(shellPath, "-i")

        if (!display.isNullOrBlank()) {
            require(display == "127.0.0.1:1" || display.matches(Regex(":[0-9]{1,2}"))) {
                "DISPLAY no válido"
            }
        }

        val process = ProcessBuilder(command)
            // Keep the host working directory outside the guest rootfs.
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
                if (!display.isNullOrBlank()) environment()["DISPLAY"] = display
            }
            .start()

        // A successful ProcessBuilder.start() does not guarantee that PRoot
        // could ptrace and execute the guest shell. Catch immediate EACCES here.
        if (process.waitFor(600, TimeUnit.MILLISECONDS)) {
            val startupOutput = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            throw IllegalStateException(
                "PRoot terminó durante el arranque (código " + process.exitValue() + "). " +
                    startupOutput.trim().ifEmpty { runtimeDiagnostics() }
            )
        }

        activeProcess = process
        return process
    }

    /** Repairs the shell's execute bit and its parent traversal permissions safely. */
    private fun ensureGuestExecutable(rootfs: File, relativePath: String) {
        val root = rootfs.canonicalFile
        val entry = File(root, relativePath)
        val target = entry.canonicalFile
        check(target.toPath().startsWith(root.toPath()) && target.isFile) {
            "El ejecutable Linux /" + relativePath + " no existe o apunta fuera del rootfs."
        }

        val directories = mutableListOf<File>()
        var parent: File? = target.parentFile
        while (parent != null && parent.toPath().startsWith(root.toPath())) {
            directories += parent
            if (parent == root) break
            parent = parent.parentFile
        }
        check(directories.lastOrNull() == root) {
            "No se pudo validar la ruta completa del ejecutable /" + relativePath + "."
        }

        try {
            directories.asReversed().forEach { directory ->
                val mode = Os.stat(directory.absolutePath).st_mode and 0x1FF
                Os.chmod(directory.absolutePath, mode or 0x1ED) // add 0755
            }
            val fileMode = Os.stat(target.absolutePath).st_mode and 0x1FF
            Os.chmod(target.absolutePath, fileMode or 0x1ED)
        } catch (error: Exception) {
            throw IllegalStateException(
                "No se pudieron reparar los permisos de /" + relativePath + ": " + error.message,
                error,
            )
        }

        check(target.canRead() && target.canExecute() &&
            directories.all { it.canRead() && it.canExecute() }) {
            "El shell /" + relativePath + " o alguno de sus directorios sigue sin permisos de lectura/ejecución."
        }
    }

    /**
     * Ensures the guest home and temporary directories exist with conventional
     * Linux permissions. Symlinks are accepted only when they resolve inside rootfs.
     */
    private fun ensureGuestDirectory(rootfs: File, relativePath: String, mode: Int) {
        val root = rootfs.canonicalFile
        val entry = File(root, relativePath)
        if (!entry.exists()) {
            check(entry.mkdirs()) { "No se pudo crear /" + relativePath + " dentro del rootfs." }
        }
        val target = entry.canonicalFile
        check(target.toPath().startsWith(root.toPath()) && target.isDirectory) {
            "La ruta /" + relativePath + " no es un directorio seguro dentro del rootfs."
        }
        try {
            Os.chmod(target.absolutePath, mode)
        } catch (error: Exception) {
            throw IllegalStateException(
                "No se pudieron corregir los permisos de /" + relativePath + ": " + error.message,
                error,
            )
        }
        check(target.canRead() && target.canWrite() && target.canExecute()) {
            "El directorio /" + relativePath + " sigue sin permisos suficientes."
        }
    }

    /** Prepares a writable, app-private host temporary directory for PRoot. */
    private fun prepareProotTempDirectory(): File {
        val cacheRoot = appContext.cacheDir.canonicalFile
        val tempDirectory = File(cacheRoot, "proot-tmp")
        if (!tempDirectory.exists()) {
            check(tempDirectory.mkdirs()) {
                "No se pudo crear PROOT_TMP_DIR en el almacenamiento temporal de DistroBoxer."
            }
        }
        val canonicalTemp = tempDirectory.canonicalFile
        check(canonicalTemp.parentFile == cacheRoot && canonicalTemp.isDirectory) {
            "PROOT_TMP_DIR no es un directorio válido."
        }
        check(canonicalTemp.canWrite()) {
            "No hay permisos de escritura en PROOT_TMP_DIR."
        }

        val probe = File.createTempFile(".proot-check-", ".tmp", canonicalTemp)
        try {
            check(probe.isFile && probe.canWrite()) {
                "PROOT_TMP_DIR existe, pero no permite crear archivos."
            }
        } finally {
            check(!probe.exists() || probe.delete()) {
                "No se pudo limpiar el archivo de prueba de PROOT_TMP_DIR."
            }
        }
        return canonicalTemp
    }

    fun isDistroInstalled(distroId: String): Boolean = runCatching {
        resolveRootfs(distroId)
    }.isSuccess

    @Synchronized
    fun writeProjectFile(distroId: String, fileName: String, contents: String): File {
        require(fileName.matches(Regex("[A-Za-z0-9_-]{1,48}\\.(py|sh|js|ts|c|cpp|rs|txt|html|css)"))) {
            "Nombre o extensión de proyecto no válida"
        }
        val rootfs = resolveRootfs(distroId)
        val rootDirectory = File(rootfs, "root")
        if (!rootDirectory.exists()) check(rootDirectory.mkdirs()) { "No se pudo crear /root" }
        require(rootDirectory.canonicalFile.parentFile == rootfs.canonicalFile) {
            "Directorio /root no válido"
        }
        val projectsDirectory = File(rootDirectory, "projects")
        if (!projectsDirectory.exists()) check(projectsDirectory.mkdirs()) {
            "No se pudo crear /root/projects"
        }
        val safeProjectsDirectory = projectsDirectory.canonicalFile
        require(safeProjectsDirectory.parentFile == rootDirectory.canonicalFile && safeProjectsDirectory.isDirectory) {
            "Directorio de proyectos no válido"
        }
        val destination = File(safeProjectsDirectory, fileName)
        require(destination.canonicalFile.parentFile == safeProjectsDirectory) {
            "Ruta de proyecto no válida"
        }
        val temporary = File(safeProjectsDirectory, ".$fileName.tmp")
        require(temporary.canonicalFile.parentFile == safeProjectsDirectory) {
            "Ruta temporal no válida"
        }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(contents.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            if (destination.exists() && !destination.delete()) error("No se pudo reemplazar el archivo del proyecto")
            check(temporary.renameTo(destination)) { "No se pudo guardar el proyecto en Linux" }
        } finally {
            temporary.delete()
        }
        return destination
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

    private fun resolveRootfs(distroId: String): File {
        require(distroId.matches(Regex("[a-z][a-z0-9_-]{1,31}"))) {
            "Identificador de distribución no válido"
        }
        val distrosDirectory = File(filesRoot, "distros")
        require(distrosDirectory.canonicalFile == distrosDirectory.absoluteFile) {
            "Directorio de distribuciones no válido"
        }
        val distroDirectory = File(distrosDirectory, distroId).canonicalFile
        require(distroDirectory.parentFile == distrosDirectory.canonicalFile) {
            "Ruta de distribución no válida"
        }
        val rootfs = File(distroDirectory, "rootfs").canonicalFile
        require(rootfs.parentFile == distroDirectory) { "Ruta rootfs no válida" }
        require(isSafeDirectory(rootfs)) { "No existe un rootfs Linux válido" }
        val releaseFile = File(rootfs, "etc/os-release")
        val resolvedRelease = releaseFile.canonicalFile
        require(
            resolvedRelease.toPath().startsWith(rootfs.toPath()) &&
                resolvedRelease.isFile
        ) {
            "La distribución no contiene un etc/os-release válido dentro de rootfs"
        }
        require(File(rootfs, "bin/sh").exists() || File(rootfs, "usr/bin/sh").exists()) {
            "La distribución no contiene un shell"
        }
        return rootfs
    }

    private fun isSafeDirectory(file: File): Boolean =
        file.exists() && file.isDirectory && file.canonicalFile == file.absoluteFile

    private fun isSafeRegularFile(file: File): Boolean =
        file.exists() && file.isFile && file.canonicalFile == file.absoluteFile
}
