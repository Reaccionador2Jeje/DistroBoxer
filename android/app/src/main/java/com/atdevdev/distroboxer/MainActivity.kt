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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.work.WorkInfo
import androidx.work.WorkManager

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
    PROGRAMMING("Programar", "{ }"),
    DESKTOP("Escritorio", "▣"),
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
            val crashReportFile = remember { File(filesDir, "last-crash.txt") }
            var crashReport by remember {
                mutableStateOf(crashReportFile.takeIf { it.isFile }?.readText().orEmpty())
            }
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
                if (crashReport.isBlank()) {
                    DistroBoxerScreen()
                } else {
                    // Keep the crashing screen out of composition until the report is read.
                    AlertDialog(
                        onDismissRequest = {
                            crashReportFile.delete()
                            crashReport = ""
                        },
                        title = { Text("Se detectó un cierre inesperado") },
                        text = {
                            Text(
                                crashReport.takeLast(6000),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                crashReportFile.delete()
                                crashReport = ""
                            }) { Text("Cerrar informe") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DistroBoxerScreen() {
    val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Desconocida"
    val runtimeAbi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
    val catalog = remember { loadDistroCatalog() }
    val context = LocalContext.current
    val prootRuntime = remember { ProotRuntime(context) }
    var prootAvailable by remember { mutableStateOf(prootRuntime.isRuntimeAvailable()) }
    var prootInstallStatus by remember {
        mutableStateOf(if (prootAvailable) "PRoot ya está instalado." else "Preparando runtime PRoot…")
    }
    LaunchedEffect(runtimeAbi) {
        if (!prootAvailable) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val abi = runtimeAbi ?: error("Este dispositivo no tiene una ABI compatible.")
                    prootRuntime.installBundledRuntime(abi)
                    check(prootRuntime.isRuntimeAvailable()) { "El binario instalado no se puede ejecutar." }
                }
            }
            prootAvailable = result.isSuccess
            prootInstallStatus = result.exceptionOrNull()?.message
                ?: "PRoot instalado y verificado para $runtimeAbi."
        }
    }
    val x11Backend = remember { EmbeddedX11Backend() }
    val x11Available = remember { x11Backend.isAvailable(context) }
    var x11Running by remember { mutableStateOf(false) }
    var x11ScreenView by remember { mutableStateOf<android.view.View?>(null) }
    var x11Status by remember { mutableStateOf("Servidor X11 detenido") }
    val workspace = remember(context) { ProjectWorkspace(context) }
    val coroutineScope = rememberCoroutineScope()
    val storageStates = remember { mutableStateMapOf<String, String>() }
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    var selectedDistroId by remember { mutableStateOf("alpine") }
    var search by remember { mutableStateOf("") }
    var projectFileName by remember { mutableStateOf("main.py") }
    var projectSource by remember {
        mutableStateOf(
            """print("Hola desde DistroBoxer!")
for numero in range(1, 6):
    print(f"Número: {numero}")""",
        )
    }
    var projectStatus by remember { mutableStateOf("Ejemplo Python listo. Guarda el archivo en tu espacio de proyectos.") }
    var terminalOutput by remember { mutableStateOf("DistroBoxer · Terminal Linux\nTodavía no hay una sesión activa.\n") }
    var terminalCommand by remember { mutableStateOf("") }
    var terminalStatus by remember { mutableStateOf("Sesión cerrada") }
    var terminalSession by remember { mutableStateOf<TerminalSession?>(null) }
    var terminalDisplay by remember { mutableStateOf<String?>(null) }

    val currentTerminalSession by rememberUpdatedState(terminalSession)
    DisposableEffect(prootRuntime, x11Backend) {
        onDispose {
            currentTerminalSession?.close()
            prootRuntime.stop()
            x11Backend.stop()
        }
    }

    suspend fun refreshDistroStates() {
        val latest = withContext(Dispatchers.IO) {
            catalog.distros.associate { distro ->
                val installed = prootRuntime.isDistroInstalled(distro.id)
                val workInfos = runCatching {
                    WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWork("distroboxer-install-${distro.id}")
                        .get()
                }.getOrDefault(mutableListOf())
                val active = workInfos.any {
                    it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.BLOCKED
                }
                val failed = workInfos.lastOrNull { it.state == WorkInfo.State.FAILED }
                val completed = workInfos.lastOrNull { it.state == WorkInfo.State.SUCCEEDED }
                val runningWork = workInfos.lastOrNull { it.state == WorkInfo.State.RUNNING }
                val liveLog = runningWork?.progress?.getString(DistroRootfsInstallWorker.KEY_LIVE_LOG)
                    ?: runningWork?.progress?.getInt(DistroArchiveDownloadWorker.KEY_PROGRESS, -1)
                        ?.takeIf { it >= 0 }
                        ?.let { "Descargando rootfs… $it%" }
                val status = when {
                    installed -> "Instalada · lista para abrir"
                    active -> liveLog ?: "Descargando o instalando en segundo plano…"
                    failed != null -> failed.outputData.getString(DistroArchiveDownloadWorker.KEY_ERROR)
                        ?.let { "Error de instalación: $it" }
                        ?: "La instalación falló. Puedes volver a intentarlo."
                    completed != null -> "La tarea terminó, pero no se encontró un rootfs válido. Reintenta la instalación."
                    else -> null
                }
                distro.id to (status to liveLog)
            }
        }
        latest.forEach { (id, result) ->
            val (status, liveLog) = result
            if (status != null) storageStates[id] = status
            else if (storageStates[id]?.contains("Instalada", ignoreCase = true) == true) {
                storageStates.remove(id)
            }
            if (liveLog != null) {
                val line = "[${id.uppercase()}] $liveLog"
                if (!terminalOutput.endsWith(line + "\n")) {
                    terminalOutput = (terminalOutput + line + "\n").takeLast(18000)
                }
            }
        }
    }

    LaunchedEffect(selectedTab) {
        refreshDistroStates()
        if (selectedTab == AppTab.DISTROS || selectedTab == AppTab.TERMINAL) {
            while (true) {
                delay(2500)
                refreshDistroStates()
            }
        }
    }

    suspend fun openLinuxShell(distroId: String = "alpine", display: String? = null): TerminalSession {
        val current = terminalSession
        if (current != null && current.isAlive() && terminalDisplay == display) return current
        if (current != null) {
            current.close()
            terminalSession = null
            prootRuntime.stop()
        }
        val process = withContext(Dispatchers.IO) { prootRuntime.startShell(distroId, display) }
        val session = TerminalSession(
            process = process,
            onOutput = { chunk -> terminalOutput = (terminalOutput + chunk).takeLast(18000) },
            onExit = { code ->
                terminalStatus = "La shell terminó (código $code)"
                terminalSession = null
                terminalDisplay = null
            },
        )
        terminalSession = session
        terminalDisplay = display
        terminalStatus = "Conectado a $distroId"
        terminalOutput += "\n[ DistroBoxer: sesión iniciada en $distroId ]\n"
        return session
    }

    fun selectDistroForTerminal(distroId: String) {
        if (selectedDistroId != distroId && terminalSession?.isAlive() == true) {
            terminalSession?.close()
            terminalSession = null
            terminalDisplay = null
            prootRuntime.stop()
            terminalStatus = "Sesión detenida al cambiar de distribución"
            terminalOutput += "\n[ DistroBoxer: distribución cambiada ]\n"
        }
        selectedDistroId = distroId
    }

    fun startLinuxShell() {
        coroutineScope.launch {
            try {
                check(prootRuntime.isDistroInstalled(selectedDistroId)) {
                    "Instala primero ${selectedDistroId} desde la pestaña Distros."
                }
                openLinuxShell(selectedDistroId)
            } catch (error: Exception) {
                terminalStatus = "No se pudo iniciar"
                terminalOutput += "\n[error] ${error.message ?: "No se pudo iniciar PRoot"}\n"
            }
        }
    }

    fun startX11() {
        runCatching {
            x11ScreenView = x11Backend.start(context)
            x11Running = true
            x11Status = "X11 activo en ${x11Backend.displayName()} · solo loopback"
        }.onFailure {
            x11Running = false
            x11Status = "No se pudo iniciar X11: ${it.message}"
        }
    }

    fun stopX11() {
        x11Backend.stop()
        x11ScreenView = null
        x11Running = false
        x11Status = "Servidor X11 detenido"
    }

    fun launchXfce() {
        coroutineScope.launch {
            try {
                check(x11Running) { "Inicia primero el servidor X11." }
                val session = openLinuxShell("alpine", x11Backend.displayName())
                session.sendLine("startxfce4")
                x11Status = "Comando startxfce4 enviado. Revisa la terminal si el escritorio no aparece."
            } catch (error: Exception) {
                x11Status = "No se pudo iniciar XFCE: ${error.message ?: "error desconocido"}. Comprueba que Alpine y XFCE estén instalados."
            }
        }
    }

    fun saveProject() {
        runCatching { workspace.save(projectFileName, projectSource) }
            .onSuccess { projectStatus = "Guardado: ${it.name}" }
            .onFailure { projectStatus = "Error al guardar: ${it.message}" }
    }

    fun loadProject() {
        runCatching { workspace.read(projectFileName) }
            .onSuccess {
                projectSource = it
                projectStatus = "Proyecto cargado: " + projectFileName
            }
            .onFailure { projectStatus = "No se pudo abrir: " + it.message }
    }

    fun runProject() {
        coroutineScope.launch {
            try {
                require(projectFileName.endsWith(".py", ignoreCase = true)) {
                    "El ejemplo actual se ejecuta con Python; usa un archivo .py"
                }
                workspace.save(projectFileName, projectSource)
                check(prootRuntime.isDistroInstalled("alpine")) { "Instala Alpine desde Distros antes de ejecutar Python." }
                val session = openLinuxShell("alpine")
                withContext(Dispatchers.IO) {
                    prootRuntime.writeProjectFile("alpine", projectFileName, projectSource)
                }
                session.sendLine("python3 /root/projects/$projectFileName")
                projectStatus = "Ejecutando $projectFileName en Alpine. Si Python falta, usa: apk add python3"
                selectedTab = AppTab.TERMINAL
            } catch (error: Exception) {
                projectStatus = "No se pudo ejecutar: ${error.message ?: "error desconocido"}. Instala Alpine primero."
            }
        }
    }
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
                                installed = storageStates[distro.id]?.contains("Instalada", ignoreCase = true) == true,
                                onOpenTerminal = {
                                    selectDistroForTerminal(distro.id)
                                    selectedTab = AppTab.TERMINAL
                                },
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
                                    if (linuxArch == null) {
                                        storageStates[distro.id] = "Arquitectura no compatible."
                                    } else {
                                        runCatching {
                                            val manifest = context.assets.open("distro-manifests/${distro.id}.json")
                                                .bufferedReader().use { JSONObject(it.readText()) }
                                            val archiveFormat = manifest.getString("archive_format")
                                            val sourceUrl = manifest.getJSONObject("rootfs_source").getString(linuxArch)
                                            val checksumUrl = manifest.getJSONObject("checksum_source").getString(linuxArch)
                                            val fileName = Uri.parse(sourceUrl).lastPathSegment ?: error("Archivo inválido")
                                            DistroDownloadScheduler.enqueueWithChecksumManifest(
                                                context, distro.id, sourceUrl, checksumUrl, fileName, archiveFormat,
                                            )
                                        }.onSuccess {
                                            storageStates[distro.id] = "Descarga e instalación iniciadas en segundo plano."
                                        }.onFailure { error ->
                                            storageStates[distro.id] = "No se pudo iniciar: ${error.message}"
                                        }
                                    }                                },
                            )
                        }
                    }
                }
                AppTab.TERMINAL -> TerminalPage(
                    prootAvailable = prootAvailable,
                    prootInstallStatus = prootInstallStatus,
                    installedDistros = catalog.distros.filter {
                        storageStates[it.id]?.contains("Instalada", ignoreCase = true) == true
                    },
                    selectedDistroId = selectedDistroId,
                    onDistroSelected = { selectDistroForTerminal(it) },
                    onOpenDistros = { selectedTab = AppTab.DISTROS },
                    terminalOutput = terminalOutput,
                    terminalCommand = terminalCommand,
                    terminalStatus = terminalStatus,
                    onCommandChange = { terminalCommand = it },
                    onStartSession = { startLinuxShell() },
                    onSendCommand = {
                        val line = terminalCommand
                        if (line.isNotBlank()) {
                            runCatching { terminalSession?.sendLine(line) }
                                .onFailure { terminalOutput += "\n[error] ${it.message}\n" }
                            terminalCommand = ""
                        }
                    },
                    onStopSession = {
                        terminalSession?.close()
                        terminalSession = null
                        prootRuntime.stop()
                        terminalStatus = "Sesión detenida"
                        terminalOutput += "\n[ DistroBoxer: sesión detenida ]\n"
                    },
                )
                AppTab.PROGRAMMING -> ProgrammingPage(
                    fileName = projectFileName,
                    source = projectSource,
                    status = projectStatus,
                    onFileNameChange = { projectFileName = it },
                    onSourceChange = { projectSource = it },
                    onLoad = { loadProject() },
                    onSave = { saveProject() },
                    onRun = { runProject() },
                )
                AppTab.DESKTOP -> DesktopPage(
                    prootAvailable = prootAvailable,
                    x11Available = x11Available,
                    x11Running = x11Running,
                    x11ScreenView = x11ScreenView,
                    x11Status = x11Status,
                    onStartX11 = { startX11() },
                    onStopX11 = { stopX11() },
                    onLaunchDesktop = { launchXfce() },
                )
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
                value = if (installedCount > 0) "Lista para usar" else "Instala una distribución",
                detail = if (installedCount > 0) {
                    "Abre cualquiera de tus distribuciones instaladas y ejecuta comandos Linux."
                } else {
                    "Instala Alpine desde Distros. Después podrás abrir su shell PRoot desde aquí."
                },
                good = installedCount > 0,
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
    installed: Boolean,
    onOpenTerminal: () -> Unit,
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
                Button(onClick = onPrepare, enabled = engineConnected && !installed) { Text("Preparar") }
                if (distro.id == "alpine" && !installed) {
                    Button(onClick = onInstall, enabled = engineConnected) { Text("Instalar") }
                }
                if (installed) {
                    Button(onClick = onOpenTerminal) { Text("Abrir terminal") }
                }
            }
            if (storageStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(storageStatus, color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
            if (distro.id != "alpine" && !installed) {
                Text("El instalador Android está disponible actualmente para Alpine.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TerminalPage(
    prootAvailable: Boolean,
    prootInstallStatus: String,
    installedDistros: List<DistroUi>,
    selectedDistroId: String,
    onDistroSelected: (String) -> Unit,
    onOpenDistros: () -> Unit,
    terminalOutput: String,
    terminalCommand: String,
    terminalStatus: String,
    onCommandChange: (String) -> Unit,
    onStartSession: () -> Unit,
    onSendCommand: () -> Unit,
    onStopSession: () -> Unit,
) {
    val selectedDistro = installedDistros.firstOrNull { it.id == selectedDistroId }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Terminal Linux", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Una terminal conectada a tus distribuciones instaladas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionCard(
            title = "Runtime PRoot",
            value = if (prootAvailable) "Instalado y verificado" else "No disponible",
            detail = prootInstallStatus,
            good = prootAvailable,
        )
        Text("Distribución", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (installedDistros.isEmpty()) {
            Text("Todavía no hay distribuciones instaladas. Instala Alpine desde Distros para abrir tu primera shell Linux.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onOpenDistros, modifier = Modifier.fillMaxWidth()) { Text("Ir a Distros") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                installedDistros.forEach { distro ->
                    FilterChip(
                        selected = selectedDistroId == distro.id,
                        onClick = { onDistroSelected(distro.id) },
                        label = { Text(distro.name) },
                    )
                }
            }
        }
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF07110F)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("●  ●  ●", color = Color(0xFF62E6B7))
                    Spacer(Modifier.weight(1f))
                    Text(terminalStatus.uppercase(), color = Color(0xFF8BA99D), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(10.dp))
                Text(terminalOutput, color = Color(0xFFD5E8DD), style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(
            value = terminalCommand,
            onValueChange = onCommandChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Comando Linux") },
            placeholder = { Text(if (selectedDistro?.id == "alpine") "apk add python3" else "ls -la") },
            shape = RoundedCornerShape(14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(
                onClick = onStartSession,
                enabled = prootAvailable && selectedDistro != null && !terminalStatus.startsWith("Conectado"),
            ) { Text(if (selectedDistro == null) "Sin distro" else "Iniciar ${selectedDistro.name}") }
            Button(onClick = onSendCommand, enabled = terminalStatus.startsWith("Conectado")) { Text("Ejecutar") }
            Button(onClick = onStopSession, enabled = terminalStatus.startsWith("Conectado")) { Text("Detener") }
        }
        Text(
            "Los comandos se ejecutan dentro de la distribución seleccionada. La sesión permanece activa mientras la app está abierta.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}


@Composable
private fun ProgrammingPage(
    fileName: String,
    source: String,
    status: String,
    onFileNameChange: (String) -> Unit,
    onSourceChange: (String) -> Unit,
    onLoad: () -> Unit,
    onSave: () -> Unit,
    onRun: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Programar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Un espacio para escribir y ejecutar código dentro de Linux.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = fileName,
            onValueChange = onFileNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Archivo Python (.py)") },
            shape = RoundedCornerShape(14.dp),
        )
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1420)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("EDITOR · PYTHON", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = source,
                    onValueChange = onSourceChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 10,
                    maxLines = 18,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    label = { Text("Código fuente") },
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(onClick = onLoad) { Text("Abrir") }
            Button(onClick = onSave) { Text("Guardar") }
        }
        Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
            Text("▶ Ejecutar en Linux")
        }
        Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        SectionCard(
            title = "Flujo de trabajo",
            value = "Editor → archivo → PRoot → Python",
            detail = "Guarda el código en el espacio privado de DistroBoxer. Al ejecutarlo, se copia a /root/projects dentro de Alpine y se lanza desde la terminal.",
            good = true,
        )
        Text(
            "Antes de ejecutar Python por primera vez, instala el intérprete desde la terminal con: apk add python3",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}


@Composable
private fun DesktopPage(
    prootAvailable: Boolean,
    x11Available: Boolean,
    x11Running: Boolean,
    x11ScreenView: android.view.View?,
    x11Status: String,
    onStartX11: () -> Unit,
    onStopX11: () -> Unit,
    onLaunchDesktop: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Text("Escritorios Linux", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Servidor X11 integrado en DistroBoxer", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF142C37)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("DISPLAY SERVER", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("X11 + PRoot", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "El servidor X11 se ejecuta dentro de DistroBoxer y muestra su pantalla en esta vista. PRoot conecta las aplicaciones Linux al display local 127.0.0.1:1.",
                    color = Color(0xFFC4D5DF),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "PRoot: " + (if (prootAvailable) "instalado" else "pendiente") +
                        " · X11: " + (if (x11Available) "integrado" else "no disponible"),
                    color = if (x11Available && prootAvailable) MaterialTheme.colorScheme.primary else Color(0xFFFFC078),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(x11Status, color = Color(0xFFC4D5DF), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartX11, enabled = x11Available && !x11Running) { Text("Iniciar X11") }
                    Button(onClick = onStopX11, enabled = x11Running) { Text("Detener") }
                }
            }
        }
        val activeView = x11ScreenView
        if (x11Running && activeView != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
            ) {
                AndroidView(
                    factory = { activeView },
                    modifier = Modifier.fillMaxWidth().height(430.dp),
                )
            }
            Button(
                onClick = onLaunchDesktop,
                enabled = prootAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Iniciar sesión XFCE")
            }
        }
        DesktopEnvironmentCard("XFCE", "Ligero y completo", "Primer escritorio que probaremos en el display integrado", "EXPERIMENTAL")
        DesktopEnvironmentCard("LXQt", "Ligero y modular", "Alternativa para una etapa posterior", "PLANIFICADO")
        DesktopEnvironmentCard("MATE", "Clásico y familiar", "Escritorio tradicional para una etapa posterior", "PLANIFICADO")
        SectionCard(
            title = "Conexión",
            value = "127.0.0.1:1 · puerto 6001",
            detail = "El listener X11 está limitado a loopback; no acepta conexiones desde la red Wi-Fi. El motor Java es ligero y no garantiza GLX ni compatibilidad total con todos los escritorios.",
            good = true,
        )
        Text(
            "Para iniciar XFCE necesitas tener Alpine instalado y el escritorio instalado dentro de esa distribución. En Alpine puedes probar desde Terminal: apk update && apk add xfce4 dbus. La compatibilidad del escritorio debe validarse en el teléfono.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}


@Composable
private fun DesktopEnvironmentCard(name: String, subtitle: String, detail: String, status: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Surface(shape = RoundedCornerShape(50), color = Color(0xFF34291B)) {
                Text(status, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = Color(0xFFFFC078), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SettingsPage(
    engineConnected: Boolean,
    deviceAbi: String,
    notificationsAllowed: Boolean,
    onRequestNotifications: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
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
