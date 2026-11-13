package com.atdevdev.distroboxer

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

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
        ensureGuestExecutable(rootfs, "bin/sh")
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
    private fun ensureGuestExecutable(rootfs: File, relativePath: String) {
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
            "El shell /$relativePath o alguno de sus directorios sigue sin permisos de lectura/ejecución."
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
