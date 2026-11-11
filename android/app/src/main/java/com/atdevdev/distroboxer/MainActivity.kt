package com.atdevdev.distroboxer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

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

private enum class AppTab(val label: String, val symbol: String) {
    HOME("Inicio", "⌂"),
    DISTROS("Distros", "▦"),
    TERMINAL("Terminal", ">_"),
    SETTINGS("Ajustes", "⚙"),
}

private fun loadDistroCatalog(): DistroCatalog = try {
    val json = JSONArray(NativeBridge.nativeSupportedDistros())
    val distros = List(json.length()) { index ->
        val item = json.getJSONObject(index)
        val architectures = item.getJSONArray("architectures")
        DistroUi(
            id = item.getString("id"),
            name = item.getString("name"),
            packageManager = item.getString("package_manager").uppercase(),
            description = "Arquitecturas: " +
                List(architectures.length()) { architectures.getString(it) }.joinToString(", "),
            bootstrap = item.getString("bootstrap"),
            archiveFormat = item.getString("archive_format"),
            homepage = item.getString("homepage"),
        )
    }
    DistroCatalog(distros, true)
} catch (_: LinkageError) {
    DistroCatalog(fallbackDistros, false)
} catch (_: Exception) {
    DistroCatalog(fallbackDistros, false)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF62E6B7),
                    onPrimary = Color(0xFF06251B),
                    secondary = Color(0xFF8AB9FF),
                    background = Color(0xFF080F1B),
                    surface = Color(0xFF111D2D),
                    surfaceVariant = Color(0xFF1A2A3E),
                    onSurface = Color(0xFFEAF2FA),
                    onSurfaceVariant = Color(0xFFA4B5C8),
                ),
            ) {
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
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    var search by remember { mutableStateOf("") }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val filteredDistros = catalog.distros.filter {
        it.name.contains(search, ignoreCase = true) ||
            it.id.contains(search, ignoreCase = true) ||
            it.packageManager.contains(search, ignoreCase = true)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF101A29)) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Text(tab.symbol, fontWeight = FontWeight.Bold) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = RoundedCornerShape(15.dp),
                        color = Color(0xFF163B36),
                    ) {
                        androidx.compose.foundation.layout.Box(
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("DB", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                        }
                    }
                    Column(Modifier.padding(start = 11.dp)) {
                        Text("DistroBoxer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("LINUX ENVIRONMENT MANAGER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(shape = RoundedCornerShape(50), color = Color(0xFF17372F)) {
                    Text(
                        "BETA 0.3",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            when (selectedTab) {
                AppTab.HOME -> HomePage(
                    distroCount = catalog.distros.size,
                    engineConnected = catalog.engineConnected,
                    deviceAbi = deviceAbi,
                    installedCount = storageStates.values.count { it.contains("instalada", true) },
                    onOpenDistros = { selectedTab = AppTab.DISTROS },
                    onOpenTerminal = { selectedTab = AppTab.TERMINAL },
                )
                AppTab.DISTROS -> {
                    Text("Tus distribuciones", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Explora los entornos disponibles para tu dispositivo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                        label = { Text("Buscar distro o gestor") },
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(filteredDistros, key = { it.id }) { distro ->
                            DistroCard(
                                distro = distro,
                                engineConnected = catalog.engineConnected,
                                storageStatus = storageStates[distro.id],
                                onPrepare = {
                                    val prepared = runCatching {
                                        NativeBridge.nativePrepareDistro(distro.id, context.filesDir.absolutePath)
                                    }.getOrDefault(false)
                                    storageStates[distro.id] = if (prepared) {
                                        "Espacio preparado. Aún falta instalar Linux."
                                    } else {
                                        "No se pudo preparar el espacio."
                                    }
                                },
                                onInstall = {
                                    val linuxArch = when {
                                        Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
                                        Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
                                        else -> null
                                    }
                                    if (distro.id != "alpine") {
                                        storageStates[distro.id] = "El instalador de esta distro todavía está en desarrollo."
                                    } else if (linuxArch == null) {
                                        storageStates[distro.id] = "Arquitectura no compatible."
                                    } else {
                                        runCatching {
                                            val manifest = context.assets.open("distro-manifests/alpine.json")
                                                .bufferedReader().use { JSONObject(it.readText()) }
                                            val sourceUrl = manifest.getJSONObject("rootfs_source").getString(linuxArch)
                                            val checksumUrl = manifest.getJSONObject("checksum_source").getString(linuxArch)
                                            val fileName = Uri.parse(sourceUrl).lastPathSegment ?: error("Archivo inválido")
                                            DistroDownloadScheduler.enqueueWithChecksumManifest(
                                                context, distro.id, sourceUrl, checksumUrl, fileName,
                                            )
                                        }.onSuccess {
                                            storageStates[distro.id] = "Descarga e instalación iniciadas en segundo plano."
                                        }.onFailure { error ->
                                            storageStates[distro.id] = "No se pudo iniciar: ${error.message}"
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                AppTab.TERMINAL -> TerminalPage()
                AppTab.SETTINGS -> SettingsPage(
                    engineConnected = catalog.engineConnected,
                    deviceAbi = deviceAbi,
                    notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HomePage(
    distroCount: Int,
    engineConnected: Boolean,
    deviceAbi: String,
    installedCount: Int,
    onOpenDistros: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF142C37)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(21.dp)) {
                    Text("TU LINUX. TUS REGLAS.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text("Un universo Linux en tu bolsillo.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Gestiona distribuciones y prepara tu entorno desde una sola aplicación.", color = Color(0xFFC4D5DF))
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onOpenDistros) { Text("Explorar distribuciones  →") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("DISTROS", distroCount.toString(), Modifier.weight(1f))
                MetricCard("INSTALADAS", installedCount.toString(), Modifier.weight(1f))
                MetricCard("ARQUITECTURA", if (deviceAbi == "arm64-v8a") "ARM64" else deviceAbi.uppercase(), Modifier.weight(1f))
            }
        }
        item {
            SectionCard(
                title = "Estado del motor",
                value = if (engineConnected) "Conectado" else "Modo demostración",
                detail = if (engineConnected) "El catálogo está sincronizado con el motor Rust." else "La biblioteca nativa no está disponible en esta compilación.",
                good = engineConnected,
            )
        }
        item {
            SectionCard(
                title = "Terminal Linux",
                value = "Próximamente",
                detail = "La interfaz está preparada; falta integrar PRoot y las sesiones de shell.",
                good = false,
            )
        }
        item {
            Button(onClick = onOpenTerminal, modifier = Modifier.fillMaxWidth()) { Text("Ver centro de terminal") }
        }
        item {
            Text("Hecho por Atdev Dev · Sin compras dentro de la app", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(13.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionCard(title: String, value: String, detail: String, good: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(17.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(value, color = if (good) MaterialTheme.colorScheme.primary else Color(0xFFFFC078), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DistroCard(
    distro: DistroUi,
    engineConnected: Boolean,
    storageStatus: String?,
    onPrepare: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(distro.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(distro.id.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
                Surface(shape = RoundedCornerShape(50), color = Color(0xFF1B342E)) {
                    Text(distro.packageManager, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(distro.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("${distro.bootstrap} · ${distro.archiveFormat}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = onPrepare, enabled = engineConnected) { Text("Preparar") }
                if (distro.id == "alpine") {
                    Button(onClick = onInstall, enabled = engineConnected) { Text("Instalar") }
                }
            }
            if (storageStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(storageStatus, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TerminalPage() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Terminal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Centro de sesiones Linux", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF07110F)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("●  ●  ●", color = Color(0xFF62E6B7))
                    Spacer(Modifier.weight(1f))
                    Text("DISTROBOXER SHELL", color = Color(0xFF8BA99D), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(22.dp))
                Text("Atdev Dev · DistroBoxer", color = Color(0xFF62E6B7), fontWeight = FontWeight.Bold)
                Text("No hay ninguna sesión Linux activa.", color = Color(0xFFC1D0C9))
                Spacer(Modifier.height(8.dp))
                Text("Cuando el runtime esté integrado, aquí podrás:", color = Color(0xFF9FB7AA))
                Text("  • Abrir una shell interactiva", color = Color(0xFF9FB7AA))
                Text("  • Cambiar entre distribuciones", color = Color(0xFF9FB7AA))
                Text("  • Ejecutar aplicaciones y escritorios gráficos", color = Color(0xFF9FB7AA))
                Spacer(Modifier.height(12.dp))
                Text("$  runtime status", color = Color(0xFF62E6B7))
                Text("  PRoot: pendiente · X11: pendiente", color = Color(0xFFFFC078))
            }
        }
        SectionCard(
            title = "Sesiones en segundo plano",
            value = "Runtime pendiente",
            detail = "El trabajo en segundo plano actual cubre descargas. Las sesiones Linux necesitarán un servicio persistente y controles para detenerlas.",
            good = false,
        )
    }
}

@Composable
private fun SettingsPage(
    engineConnected: Boolean,
    deviceAbi: String,
    notificationsAllowed: Boolean,
    onRequestNotifications: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionCard("Motor nativo", if (engineConnected) "Conectado" else "No disponible", "Motor Rust y puente JNI.", engineConnected)
        SectionCard("Arquitectura", deviceAbi, "ABI principal detectada por Android.", true)
        SectionCard("Notificaciones", if (notificationsAllowed) "Permitidas" else "Desactivadas", "Se usan para mostrar el progreso de tareas en segundo plano.", notificationsAllowed)
        if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) {
            Button(onClick = onRequestNotifications) { Text("Permitir notificaciones") }
        }
        SectionCard("Privacidad", "Almacenamiento privado", "Las distribuciones se guardan dentro del espacio privado de DistroBoxer. No se solicita acceso general a tus archivos.", true)
        SectionCard("Modelo de la app", "Gratis y de código abierto", "Sin anuncios, suscripciones ni compras integradas previstas.", true)
        Text("DistroBoxer · Atdev Dev · Versión 0.3.0", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}
