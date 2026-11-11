package com.atdevdev.distroboxer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import org.json.JSONArray

private data class DistroUi(
    val id: String,
    val name: String,
    val packageManager: String,
    val description: String,
    val bootstrap: String,
    val archiveFormat: String,
    val homepage: String,
)

private val fallbackDistros = listOf(
    DistroUi("debian", "Debian", "APT", "Estable y versátil", "OCI", "oci", "https://www.debian.org/"),
    DistroUi("ubuntu", "Ubuntu", "APT", "Popular y fácil de usar", "OCI", "oci", "https://ubuntu.com/"),
    DistroUi("alpine", "Alpine Linux", "APK", "Ligera y minimalista", "Minirootfs", "tar.gz", "https://alpinelinux.org/"),
    DistroUi("arch", "Arch Linux", "PACMAN", "Rolling release", "Bootstrap", "tar.zst", "https://archlinux.org/"),
    DistroUi("fedora", "Fedora", "DNF", "Tecnología y software reciente", "OCI", "oci", "https://fedoraproject.org/"),
    DistroUi("opensuse", "openSUSE", "ZYPPER", "Distribución comunitaria", "OCI", "oci", "https://www.opensuse.org/"),
    DistroUi("void", "Void Linux", "XBPS", "Independiente y ligera", "Rootfs tarball", "tar.xz", "https://voidlinux.org/"),
    DistroUi("kali", "Kali Linux", "APT", "Distribución especializada", "OCI", "oci", "https://www.kali.org/"),
)

private data class DistroCatalog(
    val distros: List<DistroUi>,
    val engineConnected: Boolean,
)

private fun loadDistroCatalog(): DistroCatalog {
    return try {
        val json = JSONArray(NativeBridge.nativeSupportedDistros())
        val distros = List(json.length()) { index ->
            val item = json.getJSONObject(index)
            val architectures = item.getJSONArray("architectures")
            DistroUi(
                id = item.getString("id"),
                name = item.getString("name"),
                packageManager = item.getString("package_manager").uppercase(),
                description = "Arquitecturas: " +
                    List(architectures.length()) { architectures.getString(it) }
                        .joinToString(", "),
                bootstrap = item.getString("bootstrap"),
                archiveFormat = item.getString("archive_format"),
                homepage = item.getString("homepage"),
            )
        }
        DistroCatalog(distros, engineConnected = true)
    } catch (_: LinkageError) {
        DistroCatalog(fallbackDistros, engineConnected = false)
    } catch (_: Exception) {
        DistroCatalog(fallbackDistros, engineConnected = false)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DistroBoxerScreen()
            }
        }
    }
}

@Composable
private fun DistroBoxerScreen() {
    val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Desconocida"
    val catalog = remember { loadDistroCatalog() }
    val context = LocalContext.current
    val storageStates = remember { mutableStateMapOf<String, String>() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("DistroBoxer", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Tu gestor de entornos Linux",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Arquitectura del dispositivo: $deviceAbi",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Motor Rust", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (catalog.engineConnected) {
                            "Conectado: catálogo cargado desde Rust."
                        } else {
                            "Motor nativo no disponible; mostrando catálogo de demostración."
                        },
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Notificaciones de progreso", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Permite notificaciones para consultar el avance de descargas e instalaciones en segundo plano.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                        ) {
                            Text("Activar notificaciones")
                        }
                    }
                }
            }

            Text("Distribuciones", style = MaterialTheme.typography.titleLarge)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(catalog.distros, key = { it.id }) { distro ->
                    DistroCard(
                        distro = distro,
                        engineConnected = catalog.engineConnected,
                        storageStatus = storageStates[distro.id],
                        onPrepare = {
                            val prepared = runCatching {
                                NativeBridge.nativePrepareDistro(
                                    distro.id,
                                    context.filesDir.absolutePath,
                                )
                            }.getOrDefault(false)

                            storageStates[distro.id] = if (prepared) {
                                "Espacio preparado. Linux todavía no está instalado."
                            } else {
                                "No se pudo preparar el espacio."
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DistroCard(
    distro: DistroUi,
    engineConnected: Boolean,
    storageStatus: String?,
    onPrepare: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(distro.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${distro.description} · ${distro.packageManager}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Base: ${distro.bootstrap} (${distro.archiveFormat}) · Instalador en desarrollo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                if (storageStatus != null) {
                    Text(
                        storageStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Button(onClick = onPrepare, enabled = engineConnected) {
                Text("Preparar espacio")
            }
        }
    }
}
