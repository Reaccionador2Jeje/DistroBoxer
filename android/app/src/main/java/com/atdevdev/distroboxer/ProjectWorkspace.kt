package com.atdevdev.distroboxer

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/** Private source-code workspace owned by DistroBoxer. */
class ProjectWorkspace(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "projects")

    init {
        if (!directory.exists()) check(directory.mkdirs()) { "No se pudo crear la carpeta de proyectos" }
        require(directory.canonicalFile == directory.absoluteFile && directory.isDirectory) {
            "Carpeta de proyectos no válida"
        }
    }

    fun read(fileName: String): String {
        require(fileName.matches(Regex("[A-Za-z0-9_-]{1,48}\\.(py|sh|js|ts|c|cpp|rs|txt|html|css)"))) {
            "Nombre de archivo no válido"
        }
        val target = File(directory, fileName)
        require(target.isFile && target.canonicalFile.parentFile == directory.canonicalFile) {
            "El proyecto no existe o la ruta no es válida"
        }
        return target.readText(StandardCharsets.UTF_8)
    }

    fun save(fileName: String, source: String): File {
        require(fileName.matches(Regex("[A-Za-z0-9_-]{1,48}\\.(py|sh|js|ts|c|cpp|rs|txt|html|css)"))) {
            "Nombre de archivo no válido"
        }
        val target = File(directory, fileName)
        require(target.canonicalFile.parentFile == directory.canonicalFile) {
            "Ruta de proyecto no válida"
        }
        val temporary = File(directory, ".$fileName.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(source.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) error("No se pudo reemplazar el archivo")
            check(temporary.renameTo(target)) { "No se pudo guardar el proyecto" }
        } finally {
            temporary.delete()
        }
        return target
    }
}
