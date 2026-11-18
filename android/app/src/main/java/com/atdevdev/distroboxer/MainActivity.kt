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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

private object DistroPalette {
    val accent = Color(0xFFC7E36A)
    val onAccent = Color(0xFF202A12)
    val secondary = Color(0xFF86BAC5)
    val background = Color(0xFF0B1317)
    val surface = Color(0xFF141F24)
    val surfaceRaised = Color(0xFF1E2D33)
    val panelStrong = Color(0xFF203438)
    val successSurface = Color(0xFF20362D)
    val warningSurface = Color(0xFF352A1F)
    val text = Color(0xFFE9ECE4)
    val muted = Color(0xFFA4B1AD)
    val mutedText = Color(0xFFB7C5C0)
    val warning = Color(0xFFE8AA68)
    val danger = Color(0xFFE47F78)
    val terminal = Color(0xFF091310)
    val terminalText = Color(0xFFD9E7D7)
    val terminalMuted = Color(0xFF8DA39A)
    val editor = Color(0xFF0D171C)
}

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
    DistroUi("arch", "Arch Linux", "PACMAN", "Rolling release · solo x86_64", "OCI", "oci", "https://archlinux.org/"),
    DistroUi("fedora", "Fedora", "DNF", "Tecnología y software reciente", "OCI", "oci", "https://fedoraproject.org/"),
    DistroUi("opensuse", "openSUSE Tumbleweed", "ZYPPER", "Distribución rolling comunitaria", "OCI", "oci", "https://www.opensuse.org/"),
    DistroUi("void", "Void Linux", "XBPS", "Independiente y ligera", "OCI", "oci", "https://voidlinux.org/"),
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
                    primary = DistroPalette.accent,
                    onPrimary = DistroPalette.onAccent,
                    secondary = DistroPalette.secondary,
                    tertiary = DistroPalette.warning,
                    background = DistroPalette.background,
                    surface = DistroPalette.surface,
                    surfaceVariant = DistroPalette.surfaceRaised,
                    onSurface = DistroPalette.text,
                    onSurfaceVariant = DistroPalette.muted,
                    error = DistroPalette.danger,
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val clipboard = LocalClipboardManager.current
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(crashReport))
                                }) { Text("Copiar error") }
                                TextButton(onClick = {
                                    crashReportFile.delete()
                                    crashReport = ""
                                }) { Text("Cerrar informe") }
                            }
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
    // QEMU is the target primary VM engine; PRoot remains legacy until QEMU is packaged and device-tested.
    val qemuRuntime = remember(context) { QemuRuntime(context) }
    val qemuAvailable = remember { qemuRuntime.isRuntimeAvailable() }
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
    val activeInstalls = remember { mutableStateMapOf<String, Boolean>() }
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    var terminalFullscreen by remember { mutableStateOf(false) }
    var selectedDistroId by remember { mutableStateOf("alpine") }
    var search by remember { mutableStateOf("") }
    var distroFilter by remember { mutableStateOf("all") }
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
    var qemuVmStatus by remember { mutableStateOf("VM QEMU sin configurar") }
    var qemuVncEnabled by remember { mutableStateOf(false) }
    val qemuVmId = "primary-vm"

    val currentTerminalSession by rememberUpdatedState(terminalSession)
    DisposableEffect(prootRuntime, qemuRuntime, x11Backend) {
        onDispose {
            currentTerminalSession?.close()
            prootRuntime.stop()
            qemuRuntime.stop()
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
                    ?: runningWork?.progress?.getString(DistroOciInstallWorker.KEY_LIVE_LOG)
                    ?: runningWork?.progress?.getInt(DistroOciDownloadWorker.KEY_PROGRESS, -1)
                        ?.takeIf { it >= 0 }
                        ?.let { "Descargando capas OCI… $it%" }
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
                distro.id to Triple(status, liveLog, active)
            }
        }
        latest.forEach { (id, result) ->
            val (status, liveLog, active) = result
            activeInstalls[id] = active
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
            qemuRuntime.stop()
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
            qemuRuntime.stop()
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

    fun createQemuDisk() {
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) { qemuRuntime.createDisk(qemuVmId, 12) }
                qemuVmStatus = "Disco QCOW2 de 12 GiB creado."
            } catch (error: Exception) {
                qemuVmStatus = "No se pudo crear el disco QEMU: ${error.message}"
            }
        }
    }

    fun startQemuVm() {
        coroutineScope.launch {
            try {
                check(qemuRuntime.isRuntimeAvailable()) {
                    "Los binarios QEMU todavía no están incluidos en esta APK."
                }
                check(qemuRuntime.hasDisk(qemuVmId)) { "Crea primero el disco QCOW2 de la VM." }
                check(qemuRuntime.hasBootInputs(qemuVmId)) {
                    "Importa un kernel y un initramfs compatibles antes de iniciar."
                }
                terminalSession?.close()
                terminalSession = null
                prootRuntime.stop()
                val process = withContext(Dispatchers.IO) {
                    qemuRuntime.start(qemuVmId, enableVnc = qemuVncEnabled)
                }
                val session = TerminalSession(
                    process = process,
                    onOutput = { chunk -> terminalOutput = (terminalOutput + chunk).takeLast(18000) },
                    onExit = { code ->
                        terminalStatus = "La VM QEMU terminó (código $code)"
                        terminalSession = null
                        terminalDisplay = null
                    },
                )
                terminalSession = session
                terminalDisplay = "qemu"
                terminalStatus = "Conectado a VM QEMU"
                qemuVmStatus = if (qemuVncEnabled) {
                    "QEMU iniciado · consola serial activa · VNC en 127.0.0.1:5901"
                } else {
                    "QEMU iniciado · consola serial activa"
                }
                terminalOutput += "\n[ DistroBoxer: máquina virtual QEMU iniciada ]\n"
            } catch (error: Exception) {
                qemuVmStatus = "No se pudo iniciar QEMU: ${error.message}"
                terminalOutput += "\n[QEMU] ${error.message}\n"
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
    val qemuKernelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) runCatching {
            qemuRuntime.importBootFile(qemuVmId, uri, "kernel.img")
        }.onSuccess { qemuVmStatus = "Kernel importado: ${it.name}" }
            .onFailure { qemuVmStatus = "Error al importar kernel: ${it.message}" }
    }
    val qemuInitrdPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) runCatching {
            qemuRuntime.importBootFile(qemuVmId, uri, "initrd.img")
        }.onSuccess { qemuVmStatus = "Initramfs importado: ${it.name}" }
            .onFailure { qemuVmStatus = "Error al importar initramfs: ${it.message}" }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val installedDistroIds = storageStates
        .filterValues { it.contains("Instalada", ignoreCase = true) }
        .keys
    val filteredDistros = catalog.distros.filter { distro ->
        val matchesSearch = search.isBlank() ||
            distro.name.contains(search, ignoreCase = true) ||
            distro.id.contains(search, ignoreCase = true) ||
            distro.packageManager.contains(search, ignoreCase = true) ||
            distro.description.contains(search, ignoreCase = true) ||
            distro.bootstrap.contains(search, ignoreCase = true) ||
            distro.archiveFormat.contains(search, ignoreCase = true)
        val matchesFilter = when (distroFilter) {
            "installed" -> distro.id in installedDistroIds
            "available" -> distro.id !in installedDistroIds
            else -> true
        }
        matchesSearch && matchesFilter
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!terminalFullscreen) {
                NavigationBar(
                    containerColor = DistroPalette.surface,
                    contentColor = DistroPalette.text,
                    tonalElevation = 0.dp,
                ) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { terminalFullscreen = false; selectedTab = tab },
                            icon = { Text(tab.symbol, fontWeight = FontWeight.Bold) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(if (terminalFullscreen) Modifier else Modifier.padding(horizontal = 18.dp)),
        ) {
            if (!terminalFullscreen) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(13.dp),
                        color = DistroPalette.accent,
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Text("db", color = DistroPalette.onAccent, fontWeight = FontWeight.Black)
                        }
                    }
                    Column(Modifier.padding(start = 11.dp)) {
                        Text("DistroBoxer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "LINUX WORKSPACE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(shape = RoundedCornerShape(9.dp), color = DistroPalette.surfaceRaised) {
                    Text(
                        "0.3.0  /  BETA",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = DistroPalette.secondary,
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
                    onOpenProgramming = { selectedTab = AppTab.PROGRAMMING },
                    onOpenDesktop = { selectedTab = AppTab.DESKTOP },
                )
                AppTab.DISTROS -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Tus distribuciones", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Explora y administra tus entornos Linux.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { coroutineScope.launch { refreshDistroStates() } }) {
                            Text("Actualizar")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                        label = { Text("Buscar distribución o gestor") },
                        trailingIcon = {
                            if (search.isNotEmpty()) {
                                TextButton(onClick = { search = "" }) { Text("Limpiar") }
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(selected = distroFilter == "all", onClick = { distroFilter = "all" }, label = { Text("Todas") })
                        FilterChip(selected = distroFilter == "installed", onClick = { distroFilter = "installed" }, label = { Text("Instaladas") })
                        FilterChip(selected = distroFilter == "available", onClick = { distroFilter = "available" }, label = { Text("Pendientes") })
                    }
                    Text(
                        "${filteredDistros.size} de ${catalog.distros.size} distribuciones",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (filteredDistros.isEmpty()) {
                            item {
                                SectionCard(
                                    title = "No hay resultados",
                                    value = "Prueba otra búsqueda o filtro",
                                    detail = "Cambia el texto de búsqueda o selecciona «Todas».",
                                    good = false,
                                )
                            }
                        }
                        items(filteredDistros, key = { it.id }) { distro ->
                            DistroCard(
                                distro = distro,
                                engineConnected = catalog.engineConnected,
                                storageStatus = storageStates[distro.id],
                                installed = storageStates[distro.id]?.contains("Instalada", ignoreCase = true) == true,
                                installBusy = activeInstalls[distro.id] == true,
                                onOpenTerminal = {
                                    selectDistroForTerminal(distro.id)
                                    terminalFullscreen = true
                                    selectedTab = AppTab.TERMINAL
                                    startLinuxShell()
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
                                            if (distro.id == "alpine") {
                                                val manifest = context.assets.open("distro-manifests/alpine.json")
                                                    .bufferedReader().use { JSONObject(it.readText()) }
                                                val sourceUrl = manifest.getJSONObject("rootfs_source").getString(linuxArch)
                                                val checksumUrl = manifest.getJSONObject("checksum_source").getString(linuxArch)
                                                val fileName = Uri.parse(sourceUrl).lastPathSegment ?: error("Archivo inválido")
                                                DistroDownloadScheduler.enqueueWithChecksumManifest(
                                                    context, distro.id, sourceUrl, checksumUrl, fileName,
                                                )
                                            } else {
                                                val manifest = context.assets.open("distro-manifests/${distro.id}.json")
                                                    .bufferedReader().use { JSONObject(it.readText()) }
                                                val architecture = if (linuxArch == "aarch64") "arm64" else "amd64"
                                                val supported = manifest.getJSONArray("architectures")
                                                check((0 until supported.length()).any { supported.getString(it) == architecture }) {
                                                    "Esta distribución no publica una imagen para $architecture."
                                                }
                                                DistroDownloadScheduler.enqueueOci(
                                                    context = context,
                                                    distroId = distro.id,
                                                    image = manifest.getString("image"),
                                                    tag = manifest.getString("tag"),
                                                    architecture = architecture,
                                                )
                                            }
                                        }.onSuccess {
                                            activeInstalls[distro.id] = true
                                            storageStates[distro.id] = "Descarga OCI y verificación iniciadas en segundo plano."
                                        }.onFailure { error ->
                                            activeInstalls[distro.id] = false
                                            storageStates[distro.id] = "No se pudo iniciar: ${error.message}"
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                AppTab.TERMINAL -> TerminalPage(
                    qemuAvailable = qemuAvailable,
                    qemuVmStatus = qemuVmStatus,
                    qemuVmRunning = qemuRuntime.isRunning(),
                    qemuDiskReady = qemuRuntime.hasDisk(qemuVmId),
                    qemuBootReady = qemuRuntime.hasBootInputs(qemuVmId),
                    qemuVncEnabled = qemuVncEnabled,
                    onImportKernel = { qemuKernelPicker.launch(arrayOf("*/*")) },
                    onImportInitrd = { qemuInitrdPicker.launch(arrayOf("*/*")) },
                    onCreateQemuDisk = { createQemuDisk() },
                    onStartQemu = { startQemuVm() },
                    onToggleQemuVnc = { qemuVncEnabled = it },
                    prootAvailable = prootAvailable,
                    prootInstallStatus = prootInstallStatus,
                    installedDistros = catalog.distros.filter {
                        storageStates[it.id]?.contains("Instalada", ignoreCase = true) == true
                    },
                    selectedDistroId = selectedDistroId,
                    onDistroSelected = { selectDistroForTerminal(it) },
                    onOpenDistros = { selectedTab = AppTab.DISTROS },
                    terminalOutput = terminalOutput,
                    onClearOutput = { terminalOutput = "DistroBoxer · Registro limpiado.\n" },
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
                        qemuRuntime.stop()
                        terminalStatus = "Sesión detenida"
                        terminalOutput += "\n[ DistroBoxer: sesión detenida ]\n"
                    },
                    fullscreen = terminalFullscreen,
                    onExitFullscreen = {
                        terminalFullscreen = false
                        selectedTab = AppTab.DISTROS
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
    onOpenProgramming: () -> Unit,
    onOpenDesktop: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DistroPalette.panelStrong),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(21.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "DISTROBOXER  /  WORKSPACE",
                            color = DistroPalette.accent,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Surface(shape = RoundedCornerShape(8.dp), color = DistroPalette.surfaceRaised) {
                            Text(
                                "OPEN SOURCE",
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                color = DistroPalette.secondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Text(
                        "Linux, listo para ponerse en marcha.",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = DistroPalette.text,
                    )
                    Text(
                        "Administra distribuciones y trabaja con tus entornos desde un solo lugar.",
                        color = DistroPalette.mutedText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onOpenDistros) { Text("Explorar distribuciones  →") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("RUNTIME", color = DistroPalette.muted, style = MaterialTheme.typography.labelSmall)
                        Text(
                            if (engineConnected) "MOTOR CONECTADO" else "MODO LIMITADO",
                            color = if (engineConnected) DistroPalette.accent else DistroPalette.warning,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("·", color = DistroPalette.muted)
                        Text(deviceAbi.uppercase(), color = DistroPalette.secondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("CATÁLOGO", distroCount.toString(), Modifier.weight(1f))
                MetricCard("INSTALADAS", installedCount.toString(), Modifier.weight(1f))
                MetricCard(
                    "ARQUITECTURA",
                    if (deviceAbi == "arm64-v8a") "ARM64" else deviceAbi.uppercase(),
                    Modifier.weight(1f),
                )
            }
        }
        item {
            SectionCard(
                title = "Motor compartido",
                value = if (engineConnected) "Conectado" else "No disponible",
                detail = if (engineConnected) {
                    "El catálogo se está leyendo desde el motor Rust."
                } else {
                    "El catálogo de respaldo está visible; algunas funciones nativas pueden no estar disponibles."
                },
                good = engineConnected,
            )
        }
        item {
            SectionCard(
                title = "Terminal Linux",
                value = if (installedCount > 0) "Hay entornos instalados" else "Todavía no hay entornos instalados",
                detail = if (installedCount > 0) {
                    "Abre una distribución instalada para iniciar una sesión cuando el runtime esté disponible."
                } else {
                    "Instala una distribución compatible desde el catálogo para preparar tu primer entorno."
                },
                good = installedCount > 0,
            )
        }
        item {
            Text("ACCESO RÁPIDO", color = DistroPalette.muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = onOpenTerminal, modifier = Modifier.weight(1f)) { Text("Terminal") }
                Button(onClick = onOpenProgramming, modifier = Modifier.weight(1f)) { Text("Programar") }
            }
            Button(onClick = onOpenDesktop, modifier = Modifier.fillMaxWidth()) { Text("Escritorios Linux") }
        }
        item {
            Text(
                "ATDEV DEV  ·  SOFTWARE LIBRE  ·  SIN COMPRAS INTEGRADAS",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = DistroPalette.surfaceRaised),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = DistroPalette.muted)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = DistroPalette.accent)
        }
    }
}

@Composable
private fun SectionCard(title: String, value: String, detail: String, good: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DistroPalette.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (good) DistroPalette.successSurface else DistroPalette.warningSurface,
                ) {
                    Text(
                        if (good) "OK" else "REVISAR",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        color = if (good) DistroPalette.accent else DistroPalette.warning,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                value,
                color = if (good) DistroPalette.accent else DistroPalette.warning,
                fontWeight = FontWeight.Bold,
            )
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
    installBusy: Boolean,
    onOpenTerminal: () -> Unit,
    onPrepare: () -> Unit,
    onInstall: () -> Unit,
) {
    val hasError = storageStatus?.contains("error", ignoreCase = true) == true ||
        storageStatus?.contains("no se pudo", ignoreCase = true) == true ||
        storageStatus?.contains("fall", ignoreCase = true) == true ||
        storageStatus?.contains("incompatible", ignoreCase = true) == true
    val isBusy = installBusy && !installed
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = DistroPalette.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(distro.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(distro.id.uppercase(), color = DistroPalette.secondary, style = MaterialTheme.typography.labelSmall)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = DistroPalette.surfaceRaised) {
                    Text(
                        distro.packageManager,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = DistroPalette.accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(distro.description, color = DistroPalette.mutedText, style = MaterialTheme.typography.bodySmall)
            Text(
                "${distro.bootstrap}  ·  ${distro.archiveFormat}",
                color = DistroPalette.muted,
                style = MaterialTheme.typography.labelSmall,
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = when {
                    installed -> DistroPalette.successSurface
                    hasError -> DistroPalette.warningSurface
                    isBusy -> DistroPalette.warningSurface
                    else -> DistroPalette.surfaceRaised
                },
            ) {
                Text(
                    when {
                        installed -> "ENTORNO INSTALADO"
                        isBusy -> "DESCARGA / INSTALACIÓN EN CURSO"
                        hasError -> "REVISAR INSTALACIÓN"
                        else -> "PERFIL DISPONIBLE"
                    },
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    color = when {
                        installed -> DistroPalette.accent
                        isBusy || hasError -> DistroPalette.warning
                        else -> DistroPalette.secondary
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPrepare,
                    enabled = engineConnected && !installed && !isBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("Preparar espacio") }
                if (distro.id == "alpine" && !installed) {
                    Button(onClick = onInstall, enabled = engineConnected && !isBusy) {
                        Text(if (isBusy) "En curso" else "Instalar")
                    }
                }
                if (installed) {
                    Button(onClick = onOpenTerminal) { Text("Abrir terminal") }
                }
            }
            if (storageStatus != null) {
                Text(
                    storageStatus,
                    color = if (installed) DistroPalette.accent else DistroPalette.mutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (hasError) {
                    CopyErrorButton(storageStatus)
                }
            }
            if (distro.id != "alpine" && !installed) {
                Text(
                    "El instalador Android para esta distribución todavía está en desarrollo. Preparar el espacio no instala Linux.",
                    color = DistroPalette.muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TerminalPage(
    qemuAvailable: Boolean,
    qemuVmStatus: String,
    qemuVmRunning: Boolean,
    qemuDiskReady: Boolean,
    qemuBootReady: Boolean,
    qemuVncEnabled: Boolean,
    onImportKernel: () -> Unit,
    onImportInitrd: () -> Unit,
    onCreateQemuDisk: () -> Unit,
    onStartQemu: () -> Unit,
    onToggleQemuVnc: (Boolean) -> Unit,
    prootAvailable: Boolean,
    prootInstallStatus: String,
    installedDistros: List<DistroUi>,
    selectedDistroId: String,
    onDistroSelected: (String) -> Unit,
    onOpenDistros: () -> Unit,
    terminalOutput: String,
    onClearOutput: () -> Unit,
    terminalCommand: String,
    terminalStatus: String,
    onCommandChange: (String) -> Unit,
    onStartSession: () -> Unit,
    onSendCommand: () -> Unit,
    onStopSession: () -> Unit,
    fullscreen: Boolean,
    onExitFullscreen: () -> Unit,
) {
    val selectedDistro = installedDistros.firstOrNull { it.id == selectedDistroId }
    if (fullscreen) {
        FullscreenTerminalPage(
            distroName = selectedDistro?.name ?: selectedDistroId,
            terminalOutput = terminalOutput,
            terminalStatus = terminalStatus,
            terminalCommand = terminalCommand,
            onCommandChange = onCommandChange,
            onSendCommand = onSendCommand,
            onStartSession = onStartSession,
            onStopSession = onStopSession,
            onClearOutput = onClearOutput,
            onExit = onExitFullscreen,
        )
    } else Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Terminal Linux", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Terminal serial de máquinas virtuales QEMU, con PRoot temporal como compatibilidad.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = DistroPalette.panelStrong),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("QEMU SYSTEM · VM PRINCIPAL", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(qemuVmStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImportKernel) { Text("Importar kernel") }
                    Button(onClick = onImportInitrd) { Text("Importar initrd") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCreateQemuDisk, enabled = qemuAvailable && !qemuDiskReady) {
                        Text(if (qemuDiskReady) "Disco creado" else "Crear disco 12 GiB")
                    }
                    Button(onClick = onStartQemu, enabled = qemuAvailable && qemuDiskReady && qemuBootReady && !qemuVmRunning) {
                        Text("Iniciar VM")
                    }
                }
                Button(onClick = onStopSession, enabled = qemuVmRunning, modifier = Modifier.fillMaxWidth()) {
                    Text("Detener VM QEMU")
                }
                FilterChip(
                    selected = qemuVncEnabled,
                    onClick = { onToggleQemuVnc(!qemuVncEnabled) },
                    label = { Text(if (qemuVncEnabled) "VNC activado" else "VNC opcional") },
                )
                Text(
                    "Kernel: " + (if (qemuBootReady) "kernel + initrd importados" else "faltan kernel/initrd") +
                        " · Disco: " + (if (qemuDiskReady) "QCOW2 listo" else "pendiente"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "La consola serial se conecta a esta terminal. VNC, si se activa, escucha solo en 127.0.0.1:5901.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        SectionCard(
            title = "PRoot · compatibilidad temporal",
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
            colors = CardDefaults.cardColors(containerColor = DistroPalette.terminal),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("●  ●  ●", color = DistroPalette.accent)
                    Spacer(Modifier.weight(1f))
                    Text(terminalStatus.uppercase(), color = DistroPalette.terminalMuted, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(10.dp))
                Text(terminalOutput, color = DistroPalette.terminalText, style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val clipboard = LocalClipboardManager.current
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(terminalOutput))
                    }) { Text("Copiar registro") }
                    TextButton(onClick = onClearOutput) { Text("Limpiar") }
                }
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
            ) { Text(if (selectedDistro == null) "Sin distro" else "Iniciar ${selectedDistro.name} · legacy") }
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
private fun FullscreenTerminalPage(
    distroName: String,
    terminalOutput: String,
    terminalStatus: String,
    terminalCommand: String,
    onCommandChange: (String) -> Unit,
    onSendCommand: () -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onClearOutput: () -> Unit,
    onExit: () -> Unit,
) {
    val connected = terminalStatus.startsWith("Conectado")
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DistroPalette.terminal)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DistroPalette.surface)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onExit) { Text("← Distros") }
            Column(Modifier.weight(1f)) {
                Text(distroName, color = DistroPalette.text, fontWeight = FontWeight.Bold)
                Text(
                    if (connected) "SESIÓN ACTIVA" else terminalStatus.uppercase(),
                    color = if (connected) DistroPalette.accent else DistroPalette.terminalMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onClearOutput) { Text("Limpiar") }
            TextButton(onClick = {
                if (connected) onStopSession() else onStartSession()
            }) { Text(if (connected) "Detener" else "Conectar") }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp, vertical = 14.dp),
        ) {
            Text(
                terminalOutput,
                color = DistroPalette.terminalText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DistroPalette.surface)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = terminalCommand,
                onValueChange = onCommandChange,
                modifier = Modifier.weight(1f),
                enabled = connected,
                singleLine = true,
                label = { Text("Escribe un comando") },
                placeholder = { Text("ls -la") },
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (connected && terminalCommand.isNotBlank()) {
                        onSendCommand()
                    }
                }),
            )
            Button(
                onClick = {
                    onSendCommand()
                    keyboard?.hide()
                },
                enabled = connected && terminalCommand.isNotBlank(),
                modifier = Modifier.height(54.dp),
            ) { Text("Enviar") }
        }
        if (!connected) {
            Text(
                "Conecta la sesión para empezar a escribir comandos.",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DistroPalette.surface)
                    .padding(start = 14.dp, end = 14.dp, bottom = 9.dp),
                color = DistroPalette.terminalMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
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
            colors = CardDefaults.cardColors(containerColor = DistroPalette.editor),
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
        if (status.contains("error", ignoreCase = true) ||
            status.contains("no se pudo", ignoreCase = true) ||
            status.contains("fall", ignoreCase = true)
        ) {
            CopyErrorButton(status)
        }
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
            colors = CardDefaults.cardColors(containerColor = DistroPalette.panelStrong),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("DISPLAY SERVER", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("X11 + PRoot", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "El servidor X11 se ejecuta dentro de DistroBoxer y muestra su pantalla en esta vista. PRoot conecta las aplicaciones Linux al display local 127.0.0.1:1.",
                    color = DistroPalette.mutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "PRoot: " + (if (prootAvailable) "instalado" else "pendiente") +
                        " · X11: " + (if (x11Available) "integrado" else "no disponible"),
                    color = if (x11Available && prootAvailable) MaterialTheme.colorScheme.primary else DistroPalette.warning,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(x11Status, color = DistroPalette.mutedText, style = MaterialTheme.typography.bodySmall)
                if (x11Status.contains("no se pudo", ignoreCase = true) ||
                    x11Status.contains("error", ignoreCase = true)
                ) {
                    CopyErrorButton(x11Status)
                }
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
            Surface(shape = RoundedCornerShape(50), color = DistroPalette.warningSurface) {
                Text(status, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = DistroPalette.warning, style = MaterialTheme.typography.labelSmall)
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

 
@Composable
private fun CopyErrorButton(errorText: String) {
    val clipboard = LocalClipboardManager.current
    TextButton(
        onClick = { clipboard.setText(AnnotatedString(errorText)) },
    ) {
        Text("Copiar error")
    }
}
