package com.atdevdev.distroboxer

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Starts an installed Linux rootfs with PRoot inside the app's own sandbox.
 * PRoot is packaged per ABI and extracted by Android into nativeLibraryDir.
 */
class ProotRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val filesRoot = appContext.filesDir.canonicalFile
    private val prootFile = File(appContext.applicationInfo.nativeLibraryDir, "libproot.so")
    private var activeProcess: Process? = null

    @Synchronized
    fun isRuntimeAvailable(): Boolean {
        return isSafeRegularFile(prootFile) && prootFile.canExecute()
    }

    /**
     * Confirms that Android extracted the ABI-matched PRoot executable into
     * the app's native-library directory. Binaries in filesDir may be blocked
     * by Android's no-execute policy, so PRoot is packaged as a native payload.
     */
    @Synchronized
    fun installBundledRuntime(abi: String) {
        require(abi == "arm64-v8a" || abi == "x86_64") {
            "Arquitectura Android no compatible con PRoot: $abi"
        }
        check(isRuntimeAvailable()) {
            "Android no extrajo PRoot en el directorio de bibliotecas nativas."
        }
    }

    /**
     * Opens an interactive login shell in the selected distribution.
     * The returned Process exposes stdin/stdout/stderr for a terminal UI.
     */
    @Synchronized
    fun startShell(distroId: String, display: String? = null): Process {
        require(activeProcess?.isAlive != true) { "Ya existe una sesión Linux activa" }
        require(isRuntimeAvailable()) { "El ejecutable PRoot todavía no está instalado" }

        val rootfs = resolveRootfs(distroId)
        // Algunos rootfs pueden llegar con los bits ejecutables de BusyBox/env
        // alterados por el proceso de extracción o por el sistema de archivos.
        // Reparamos únicamente los ejecutables de arranque, tras resolver sus
        // symlinks y comprobar que siguen dentro del rootfs privado.
        repairShellAndLoader(rootfs)
        // PRoot crea archivos auxiliares en este directorio del host Android.
        // No debe confundirse con TMPDIR=/tmp, que pertenece al Linux invitado.
        val prootTempDirectory = prepareProotTempDirectory()
        // Ejecutamos el shell directamente: /usr/bin/env no es necesario y
        // puede fallar en rootfs Android/PRoot por permisos o enlaces BusyBox.
        // El entorno se entrega desde ProcessBuilder para evitar ese execve extra.
        val command = mutableListOf(
            prootFile.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-0",
            "-r", rootfs.absolutePath,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",
            "-b", "/apex",
            "/bin/sh", "-i",
        )
        if (!display.isNullOrBlank()) {
            require(display == "127.0.0.1:1" || display.matches(Regex(":[0-9]{1,2}"))) {
                "DISPLAY no válido"
            }
        }

        val process = ProcessBuilder(command)
            .directory(rootfs)
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

        activeProcess = process
        return process
    }

    /**
     * Comprueba un ejecutable del invitado y restaura su bit de ejecución si falta.
     * Los enlaces simbólicos se resuelven antes de cambiar permisos para evitar
     * modificar rutas que apunten fuera del rootfs de la distribución.
     */
    private fun ensureGuestExecutable(rootfs: File, relativePath: String): File {
        val root = rootfs.canonicalFile
        val entry = File(root, relativePath)
        val target = entry.canonicalFile
        check(target.toPath().startsWith(root.toPath()) && target.isFile) {
            "El ejecutable Linux /$relativePath no existe o apunta fuera del rootfs."
        }

        // Android puede extraer correctamente el archivo, pero dejar algún
        // directorio padre sin permiso de recorrido. Linux devuelve EACCES
        // incluso cuando el propio shell tiene el bit ejecutable.
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

        try {
            directories.asReversed().forEach { directory ->
                val mode = Os.stat(directory.absolutePath).st_mode and 0x1FF
                Os.chmod(directory.absolutePath, mode or 0x1ED)
            }
            val fileMode = Os.stat(target.absolutePath).st_mode and 0x1FF
            Os.chmod(target.absolutePath, fileMode or 0x1ED)
        } catch (error: Exception) {
            throw IllegalStateException(
                "No se pudieron reparar los permisos de /$relativePath: ${error.message}",
                error,
            )
        }

        check(target.canRead() && target.canExecute() &&
            directories.all { it.canRead() && it.canExecute() }) {
            "El ejecutable /$relativePath o alguno de sus directorios sigue sin permisos de lectura/ejecución."
        }
        return target
    }

    /** Repairs the shell and its exact ELF interpreter before validation or launch. */
    private fun repairShellAndLoader(rootfs: File) {
        val shell = ensureGuestExecutable(rootfs, "bin/sh")
        readElfInterpreter(shell)?.let { interpreter ->
            require(interpreter.startsWith("/") && !interpreter.split("/").contains("..")) {
                "La ruta del cargador ELF de /bin/sh no es válida."
            }
            ensureGuestExecutable(rootfs, interpreter.removePrefix("/"))
        }
    }

    /** Reads the PT_INTERP segment to locate the exact ELF dynamic loader. */
    private fun readElfInterpreter(executable: File): String? {
        RandomAccessFile(executable, "r").use { file ->
            if (file.length() < 52L) return null
            val ident = ByteArray(16)
            file.readFully(ident)
            if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() ||
                ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
            ) return null

            val byteOrder = when (ident[5].toInt() and 0xff) {
                1 -> ByteOrder.LITTLE_ENDIAN
                2 -> ByteOrder.BIG_ENDIAN
                else -> error("El ejecutable /bin/sh usa un formato ELF desconocido.")
            }
            val is64Bit = when (ident[4].toInt() and 0xff) {
                1 -> false
                2 -> true
                else -> error("El ejecutable /bin/sh usa una clase ELF desconocida.")
            }
            val headerSize = if (is64Bit) 64 else 52
            require(file.length() >= headerSize.toLong()) { "La cabecera ELF de /bin/sh está truncada." }
            val header = ByteArray(headerSize)
            file.seek(0)
            file.readFully(header)
            val view = ByteBuffer.wrap(header).order(byteOrder)
            val programHeaderOffset = if (is64Bit) view.getLong(32) else view.getInt(28).toLong() and 0xffffffffL
            val entrySize = view.getShort(if (is64Bit) 54 else 42).toInt() and 0xffff
            val entryCount = view.getShort(if (is64Bit) 56 else 44).toInt() and 0xffff
            val minimumEntrySize = if (is64Bit) 56 else 32
            require(entrySize >= minimumEntrySize && entryCount <= 1024 && programHeaderOffset >= 0L) {
                "La tabla ELF de /bin/sh no es válida."
            }

            repeat(entryCount) { index ->
                val offset = programHeaderOffset + index.toLong() * entrySize.toLong()
                require(offset >= 0L && offset + entrySize.toLong() <= file.length()) {
                    "La tabla de segmentos ELF de /bin/sh está truncada."
                }
                val programHeader = ByteArray(entrySize)
                file.seek(offset)
                file.readFully(programHeader)
                val ph = ByteBuffer.wrap(programHeader).order(byteOrder)
                if (ph.getInt(0) == 3) { // PT_INTERP
                    val interpreterOffset = if (is64Bit) ph.getLong(8) else ph.getInt(4).toLong() and 0xffffffffL
                    val interpreterSize = if (is64Bit) ph.getLong(32) else ph.getInt(16).toLong() and 0xffffffffL
                    require(interpreterOffset >= 0L && interpreterSize >= 2L && interpreterSize <= 4096L &&
                        interpreterOffset + interpreterSize <= file.length()
                    ) { "El segmento PT_INTERP de /bin/sh no es válido." }
                    val bytes = ByteArray(interpreterSize.toInt())
                    file.seek(interpreterOffset)
                    file.readFully(bytes)
                    val end = bytes.indexOf(0.toByte())
                    require(end > 0) { "La ruta del cargador ELF de /bin/sh no es válida." }
                    return String(bytes, 0, end, StandardCharsets.UTF_8)
                }
            }
            return null
        }
    }

    /**
     * Prepara y prueba el directorio temporal del host que PRoot utiliza para
     * sus archivos internos. Se valida la ruta canónica para no aceptar symlinks.
     */
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

    /**
     * Executes a real, non-interactive PRoot smoke test before an installation
     * is reported as ready. This validates the guest shell, writable runtime
     * directories, distribution identity, and its native package manager.
     */
    @Synchronized
    fun validateDistro(distroId: String) {
        require(activeProcess?.isAlive != true) { "Cierra la sesión Linux antes de validar la instalación." }
        require(isRuntimeAvailable()) { "El runtime PRoot no está disponible." }

        val rootfs = resolveRootfs(distroId)
        repairShellAndLoader(rootfs)
        ensureGuestDirectory(rootfs, "root", 0x1C0)
        ensureGuestDirectory(rootfs, "tmp", 0x3FF)
        val tempDirectory = prepareProotTempDirectory()
        val packageManager = when (distroId) {
            "debian", "ubuntu", "kali" -> "apt-get"
            "alpine" -> "apk"
            "arch" -> "pacman"
            "fedora" -> "dnf"
            "opensuse" -> "zypper"
            "void" -> "xbps-install"
            else -> error("No hay una prueba de gestor de paquetes para $distroId")
        }
        val script = "set -eu; test -r /etc/os-release; test -w /root; test -w /tmp; command -v $packageManager >/dev/null; echo DISTROBOXER_PROOT_OK"
        val command = listOf(
            prootFile.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-0",
            "-r", rootfs.absolutePath,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",
            "-b", "/apex",
            "/bin/sh", "-c", script,
        )
        val outputFile = File.createTempFile("proot-validation-", ".log", appContext.cacheDir)
        val process = ProcessBuilder(command)
            .directory(rootfs)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .apply {
                environment().clear()
                environment()["HOME"] = "/root"
                environment()["USER"] = "root"
                environment()["LOGNAME"] = "root"
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["LANG"] = "C.UTF-8"
                environment()["PROOT_TMP_DIR"] = tempDirectory.absolutePath
                environment()["TMPDIR"] = "/tmp"
            }
            .start()

        try {
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("La prueba PRoot de $distroId superó el tiempo límite.")
            }
            val output = outputFile.readText()
            check(process.exitValue() == 0 && output.contains("DISTROBOXER_PROOT_OK")) {
                "La instalación de $distroId no superó la prueba PRoot (código ${process.exitValue()}): ${output.takeLast(1200)}"
            }
        } finally {
            outputFile.delete()
        }
    }

    private fun ensureGuestDirectory(rootfs: File, relativePath: String, mode: Int) {
        val root = rootfs.canonicalFile
        val directory = File(root, relativePath)
        if (!directory.exists()) {
            check(directory.mkdirs()) { "No se pudo crear /$relativePath en el rootfs." }
        }
        val resolved = directory.canonicalFile
        check(resolved.toPath().startsWith(root.toPath()) && resolved.isDirectory) {
            "El directorio /$relativePath no es seguro dentro del rootfs."
        }
        Os.chmod(resolved.absolutePath, mode)
        check(resolved.canRead() && resolved.canWrite() && resolved.canExecute()) {
            "El directorio /$relativePath no tiene permisos suficientes."
        }
    }

    /** Returns true only when a validated rootfs exists for this distro. */
    fun isDistroInstalled(distroId: String): Boolean = runCatching {
        resolveRootfs(distroId)
    }.isSuccess

    /**
     * Copies a source file into /root/projects inside the selected Linux rootfs and returns its destination.
     * The project name is restricted to a plain filename; no path fragments are accepted.
     */
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
