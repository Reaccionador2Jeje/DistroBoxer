package com.atdevdev.distroboxer

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/** Private source-code workspace owned by DistroBoxer. */
class ProjectWorkspace(context: Context) {
    private val filesDirectory = context.applicationContext.filesDir
    private val directory = File(filesDirectory, "projects")

    /**
     * Create and validate the workspace only when an operation needs it.
     * A storage problem must not crash MainActivity during Compose startup.
     */
    private fun ensureDirectory(): File {
        val base = filesDirectory.canonicalFile
        val workspace = directory.canonicalFile

        require(workspace.parentFile == base) {
            "La carpeta de proyectos está fuera del almacenamiento privado"
        }

        if (!workspace.exists()) {
            check(workspace.mkdirs() || workspace.isDirectory) {
                "No se pudo crear la carpeta de proyectos"
            }
        }

        check(workspace.isDirectory) {
            "La ruta de proyectos existe, pero no es una carpeta"
        }

        return workspace
    }

    private fun projectFile(fileName: String, workspace: File): File {
        require(fileName.matches(Regex("[A-Za-z0-9_-]{1,48}\\.(py|sh|js|ts|c|cpp|rs|txt|html|css)"))) {
            "Nombre de archivo no válido"
        }

        val target = File(workspace, fileName).canonicalFile
        require(target.parentFile == workspace) {
            "La ruta del proyecto no es válida"
        }
        return target
    }

    fun read(fileName: String): String {
        val workspace = ensureDirectory()
        val target = projectFile(fileName, workspace)
        require(target.isFile) {
            "El proyecto no existe"
        }
        return target.readText(StandardCharsets.UTF_8)
    }

    fun save(fileName: String, source: String): File {
        val workspace = ensureDirectory()
        val target = projectFile(fileName, workspace)
        val temporary = File(workspace, ".${target.name}.tmp")

        try {
            FileOutputStream(temporary).use { output ->
                output.write(source.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }

            // Replace the destination only after the complete temporary file exists.
            if (target.exists()) {
                check(target.isFile && target.delete()) {
                    "No se pudo reemplazar el archivo existente"
                }
            }
            check(temporary.renameTo(target)) {
                "No se pudo guardar el proyecto"
            }
        } finally {
            temporary.delete()
        }

        return target
    }
}
