use portable_pty::{native_pty_system, CommandBuilder, PtySize};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::Duration;
use tauri::Emitter;

#[derive(Serialize)]
struct DistroEntry {
    id: &'static str,
    name: &'static str,
    package_manager: &'static str,
    architectures: &'static [&'static str],
    homepage: &'static str,
    bootstrap: &'static str,
    archive_format: &'static str,
}

#[tauri::command]
fn list_distributions() -> Vec<DistroEntry> {
    distroboxer::supported_distros()
        .iter()
        .map(|distro| DistroEntry {
            id: distro.id,
            name: distro.name,
            package_manager: distro.package_manager,
            architectures: distro.architectures,
            homepage: distro.homepage,
            bootstrap: distro.bootstrap,
            archive_format: distro.archive_format,
        })
        .collect()
}

#[derive(Serialize, Clone)]
struct TerminalOutput {
    session_id: u64,
    data: String,
}

#[derive(Serialize, Clone)]
struct TerminalExit {
    session_id: u64,
    code: Option<u32>,
}

struct TerminalSession {
    master: Box<dyn portable_pty::MasterPty + Send>,
    writer: Box<dyn Write + Send>,
    child: Arc<Mutex<Box<dyn portable_pty::Child + Send + Sync>>>,
}

static SESSIONS: OnceLock<Mutex<HashMap<u64, TerminalSession>>> = OnceLock::new();
static NEXT_SESSION_ID: AtomicU64 = AtomicU64::new(1);

fn sessions() -> &'static Mutex<HashMap<u64, TerminalSession>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn storage_directory() -> Result<PathBuf, String> {
    #[cfg(target_os = "linux")]
    {
        let base = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".local/share")))
            .ok_or_else(|| "No se pudo resolver el directorio de datos del usuario".to_string())?;
        return Ok(base.join("distroboxer"));
    }
    #[cfg(target_os = "windows")]
    {
        let base = std::env::var_os("LOCALAPPDATA")
            .or_else(|| std::env::var_os("APPDATA"))
            .map(PathBuf::from)
            .ok_or_else(|| "No se pudo resolver LOCALAPPDATA".to_string())?;
        return Ok(base.join("DistroBoxer"));
    }
    #[cfg(target_os = "macos")]
    {
        let home = std::env::var_os("HOME")
            .map(PathBuf::from)
            .ok_or_else(|| "No se pudo resolver HOME".to_string())?;
        return Ok(home.join("Library/Application Support/DistroBoxer"));
    }
}

#[tauri::command]
fn start_terminal(app: tauri::AppHandle, distro_id: String, cols: u16, rows: u16) -> Result<u64, String> {
    #[cfg(target_os = "linux")]
    {
        let storage = storage_directory()?;
        let backend = distroboxer::LinuxProotBackend::discover(storage)
            .map_err(|e| format!("No se encontró PRoot: {e}"))?;
        let rootfs = backend.validated_rootfs(&distro_id)
            .map_err(|e| format!("No se pudo abrir {distro_id}: {e}"))?;
        let pty_system = native_pty_system();
        let pair = pty_system.openpty(PtySize {
            rows: rows.clamp(10, 100),
            cols: cols.clamp(40, 240),
            pixel_width: 0,
            pixel_height: 0,
        }).map_err(|e| format!("No se pudo crear PTY: {e}"))?;

        let mut command = CommandBuilder::new(backend.proot_path());
        command.arg("-0");
        command.arg("-r");
        command.arg(rootfs.as_os_str());
        command.arg("-w");
        command.arg("/root");
        command.arg("/bin/sh");
        command.arg("-l");
        command.env_clear();
        command.env("HOME", "/root");
        command.env("USER", "root");
        command.env("LOGNAME", "root");
        command.env("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        command.env("TERM", "xterm-256color");
        command.env("LANG", "C.UTF-8");

        let child = pair.slave.spawn_command(command)
            .map_err(|e| format!("No se pudo iniciar PRoot: {e}"))?;
        drop(pair.slave);

        let mut reader = pair.master.try_clone_reader()
            .map_err(|e| format!("No se pudo conectar la salida de la terminal: {e}"))?;
        let writer = pair.master.take_writer()
            .map_err(|e| format!("No se pudo conectar la entrada de la terminal: {e}"))?;

        let session_id = NEXT_SESSION_ID.fetch_add(1, Ordering::Relaxed);
        let child = Arc::new(Mutex::new(child));
        sessions().lock().map_err(|_| "Gestor de sesiones bloqueado")?.insert(
            session_id,
            TerminalSession { master: pair.master, writer, child: Arc::clone(&child) },
        );

        let output_app = app.clone();
        thread::Builder::new().name(format!("distroboxer-pty-{session_id}")).spawn(move || {
            let mut buffer = [0_u8; 4096];
            loop {
                match reader.read(&mut buffer) {
                    Ok(0) => break,
                    Ok(count) => {
                        let data = String::from_utf8_lossy(&buffer[..count]).into_owned();
                        let _ = output_app.emit("terminal-output", TerminalOutput { session_id, data });
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                    Err(_) => break,
                }
            }
        }).map_err(|e| format!("No se pudo iniciar lector PTY: {e}"))?;

        let monitor_app = app.clone();
        thread::Builder::new().name(format!("distroboxer-child-{session_id}")).spawn(move || {
            loop {
                let result = child.lock().ok().and_then(|mut process| process.try_wait().ok()).flatten();
                if let Some(status) = result {
                    let _ = monitor_app.emit("terminal-exit", TerminalExit {
                        session_id,
                        code: Some(status.exit_code()),
                    });
                    if let Ok(mut active) = sessions().lock() {
                        active.remove(&session_id);
                    }
                    break;
                }
                thread::sleep(Duration::from_millis(250));
            }
        }).map_err(|e| format!("No se pudo vigilar la sesión: {e}"))?;

        Ok(session_id)
    }
    #[cfg(not(target_os = "linux"))]
    {
        let _ = (app, distro_id, cols, rows);
        Err("El runtime de terminal PRoot todavía no está integrado en este sistema operativo".into())
    }
}

#[tauri::command]
fn write_terminal(session_id: u64, data: String) -> Result<(), String> {
    let mut active = sessions().lock().map_err(|_| "Gestor de sesiones bloqueado")?;
    let session = active.get_mut(&session_id).ok_or("La sesión ya no existe")?;
    session.writer.write_all(data.as_bytes()).map_err(|e| format!("Error al escribir en la terminal: {e}"))?;
    session.writer.flush().map_err(|e| format!("Error al enviar datos a la terminal: {e}"))
}

#[tauri::command]
fn resize_terminal(session_id: u64, cols: u16, rows: u16) -> Result<(), String> {
    let active = sessions().lock().map_err(|_| "Gestor de sesiones bloqueado")?;
    let session = active.get(&session_id).ok_or("La sesión ya no existe")?;
    session.master.resize(PtySize {
        rows: rows.clamp(10, 100),
        cols: cols.clamp(40, 240),
        pixel_width: 0,
        pixel_height: 0,
    }).map_err(|e| format!("No se pudo redimensionar la terminal: {e}"))
}

#[tauri::command]
fn stop_terminal(session_id: u64) -> Result<(), String> {
    let mut active = sessions().lock().map_err(|_| "Gestor de sesiones bloqueado")?;
    if let Some(session) = active.remove(&session_id) {
        if let Ok(mut child) = session.child.lock() {
            let _ = child.kill();
        }
    }
    Ok(())
}

#[tauri::command]
fn list_terminal_sessions() -> Result<Vec<u64>, String> {
    Ok(sessions().lock().map_err(|_| "Gestor de sesiones bloqueado")?
        .keys().copied().collect())
}


#[derive(Deserialize)]
struct DistroInstallManifest {
    archive_format: String,
    rootfs_source: HashMap<String, String>,
    checksum_source: HashMap<String, String>,
}

fn distro_manifest(id: &str) -> Option<&'static str> {
    match id {
        "alpine" => Some(include_str!("../../android/app/src/main/assets/distro-manifests/alpine.json")),
        "debian" => Some(include_str!("../../android/app/src/main/assets/distro-manifests/debian.json")),
        "ubuntu" => Some(include_str!("../../android/app/src/main/assets/distro-manifests/ubuntu.json")),
        "arch" => Some(include_str!("../../android/app/src/main/assets/distro-manifests/arch.json")),
        "fedora" => Some(include_str!("../../android/app/src/main/assets/distro-manifests/fedora.json")),
        "opensuse" => Some(include_str!("../../android/app/src/main/assets/distro-manifests/opensuse.json")),
        "void" => Some(include_str!("../../android/app/src/main/assets/distro-manifests/void.json")),
        "kali" => Some(include_str!("../../android/app/src/main/assets/distro-manifests/kali.json")),
        _ => None,
    }
}

fn is_trusted_download_url(url: &reqwest::Url) -> bool {
    if url.scheme() != "https" {
        return false;
    }

    let Some(host) = url.host_str() else {
        return false;
    };

    // The Linux Containers image server redirects downloads to regional
    // mirrors such as ca.images.linuxcontainers.org. Accept only the official
    // image-server hostname and its subdomains, never lookalike suffixes.
    host == "dl-cdn.alpinelinux.org"
        || host == "images.linuxcontainers.org"
        || host.ends_with(".images.linuxcontainers.org")
}

fn download_client() -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(900))
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            if is_trusted_download_url(attempt.url()) {
                attempt.follow()
            } else {
                attempt.stop()
            }
        }))
        .build()
        .map_err(|error| format!("No se pudo preparar el cliente HTTPS: {error}"))
}

fn expected_archive_sha256(
    client: &reqwest::blocking::Client,
    checksum_url: &str,
    file_name: &str,
) -> Result<String, String> {
    let url = reqwest::Url::parse(checksum_url).map_err(|error| error.to_string())?;
    if !is_trusted_download_url(&url) {
        return Err("El servidor de checksums no está autorizado".into());
    }
    let body = client
        .get(url)
        .send()
        .map_err(|error| format!("No se pudo descargar SHA256SUMS: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Error HTTP al descargar SHA256SUMS: {error}"))?
        .text()
        .map_err(|error| format!("No se pudo leer SHA256SUMS: {error}"))?;

    for line in body.lines().filter(|line| !line.trim().is_empty()) {
        let mut fields = line.split_whitespace();
        let digest = fields.next().unwrap_or_default();
        let listed_name = fields.next().unwrap_or_default()
            .trim_start_matches('*')
            .rsplit('/')
            .next()
            .unwrap_or_default();
        if digest.len() == 64
            && digest.bytes().all(|byte| byte.is_ascii_hexdigit())
            && (listed_name == file_name || listed_name.is_empty())
        {
            return Ok(digest.to_ascii_lowercase());
        }
    }
    Err(format!("SHA256SUMS no contiene el hash de {file_name}"))
}

fn download_verified_archive(
    client: &reqwest::blocking::Client,
    source_url: &str,
    destination: &Path,
    expected_sha256: &str,
) -> Result<(), String> {
    let url = reqwest::Url::parse(source_url).map_err(|error| error.to_string())?;
    if !is_trusted_download_url(&url) {
        return Err("La URL del rootfs no pertenece a un servidor autorizado".into());
    }
    let mut response = client
        .get(url)
        .send()
        .map_err(|error| format!("Error al descargar el rootfs: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Error HTTP al descargar el rootfs: {error}"))?;
    if response.content_length().is_some_and(|size| size > 4 * 1024 * 1024 * 1024) {
        return Err("El archivo supera el límite de descarga de 4 GiB".into());
    }

    let mut output = File::create(destination)
        .map_err(|error| format!("No se pudo crear el archivo de descarga: {error}"))?;
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let count = response.read(&mut buffer)
            .map_err(|error| format!("La descarga se interrumpió: {error}"))?;
        if count == 0 {
            break;
        }
        total = total.saturating_add(count as u64);
        if total > 4 * 1024 * 1024 * 1024 {
            let _ = fs::remove_file(destination);
            return Err("El archivo supera el límite de descarga de 4 GiB".into());
        }
        output.write_all(&buffer[..count])
            .map_err(|error| format!("No se pudo guardar la descarga: {error}"))?;
    }
    output.sync_all().map_err(|error| error.to_string())?;

    if !distroboxer::verify_sha256(destination, expected_sha256)
        .map_err(|error| error.to_string())?
    {
        let _ = fs::remove_file(destination);
        return Err("La verificación SHA-256 del rootfs falló".into());
    }
    Ok(())
}

#[cfg(target_os = "windows")]
fn decompress_archive_to_tar(
    archive: &Path,
    archive_format: &str,
    tar_path: &Path,
) -> Result<(), String> {
    let file = File::open(archive).map_err(|error| error.to_string())?;
    let mut decoder: Box<dyn Read> = match archive_format {
        "tar.gz" => Box::new(flate2::read::GzDecoder::new(file)),
        "tar.xz" => Box::new(xz2::read::XzDecoder::new(file)),
        "tar.zst" => Box::new(
            zstd::stream::read::Decoder::new(file)
                .map_err(|error| format!("No se pudo abrir el archivo Zstandard: {error}"))?,
        ),
        "tar" => Box::new(file),
        _ => return Err(format!("Formato de archivo no compatible: {archive_format}")),
    };
    let mut output = File::create(tar_path).map_err(|error| error.to_string())?;
    std::io::copy(&mut decoder, &mut output)
        .map_err(|error| format!("No se pudo preparar el tar para WSL: {error}"))?;
    output.sync_all().map_err(|error| error.to_string())
}

#[tauri::command]
fn install_distribution(distro_id: String) -> Result<String, String> {
    let distro = distroboxer::find_distro(&distro_id)
        .ok_or_else(|| "Distribución no compatible".to_string())?;
    let manifest_text = distro_manifest(&distro_id)
        .ok_or_else(|| "No existe un manifiesto de instalación para esta distribución".to_string())?;
    let manifest: DistroInstallManifest = serde_json::from_str(manifest_text)
        .map_err(|error| format!("Manifiesto inválido: {error}"))?;

    let architecture = match std::env::consts::ARCH {
        "x86_64" => "x86_64",
        "aarch64" => "aarch64",
        other => return Err(format!("Arquitectura no compatible: {other}")),
    };
    if !distro.architectures.contains(&architecture) {
        return Err(format!("{} no ofrece una imagen para {architecture}", distro.name));
    }
    if manifest.archive_format != distro.archive_format {
        return Err("El formato del manifiesto no coincide con el catálogo".into());
    }
    let source_url = manifest.rootfs_source.get(architecture)
        .ok_or_else(|| "No hay descarga para esta arquitectura".to_string())?;
    let checksum_url = manifest.checksum_source.get(architecture)
        .ok_or_else(|| "No hay checksums para esta arquitectura".to_string())?;
    let file_name = source_url.rsplit('/').next()
        .filter(|name| !name.is_empty())
        .ok_or_else(|| "Nombre de archivo de rootfs inválido".to_string())?;

    let storage = storage_directory()?;
    let manager = distroboxer::StorageManager::new(&storage);
    let distro_dir = manager.prepare_distro(&distro_id)
        .map_err(|error| format!("No se pudo preparar el almacenamiento: {error}"))?;

    // Keep failures visible in metadata even when they happen before extraction
    // (for example, a checksum endpoint or regional mirror is unavailable).
    let install_result = (|| -> Result<String, String> {
        let archive_path = distro_dir.join("downloads").join(file_name);
        let client = download_client()?;
        let expected_hash = expected_archive_sha256(&client, checksum_url, file_name)?;
        download_verified_archive(&client, source_url, &archive_path, &expected_hash)?;

        #[cfg(target_os = "linux")]
        {
            manager.install_archive_with_progress(
                &distro_id,
                &archive_path,
                &manifest.archive_format,
                &expected_hash,
                |_| {},
            ).map_err(|error| format!("Falló la instalación de {}: {error}", distro.name))?;
            Ok(format!("{} instalada correctamente.", distro.name))
        }

        #[cfg(target_os = "windows")]
        {
            let tar_path = distro_dir.join("downloads").join(format!("{distro_id}-wsl-import.tar"));
            decompress_archive_to_tar(&archive_path, &manifest.archive_format, &tar_path)?;
            let install_location = storage.join("wsl").join(format!("DistroBoxer-{distro_id}"));
            let backend = distroboxer::WindowsWsl2Backend::discover()
                .map_err(|error| format!("No se pudo localizar WSL: {error}"))?;
            backend.import_rootfs_tarball(&distro_id, install_location, &tar_path)
                .map_err(|error| format!("Falló la importación a WSL2: {error}"))?;
            Ok(format!("{} importada en WSL2 correctamente.", distro.name))
        }

        #[cfg(target_os = "macos")]
        {
            let _ = (manager, archive_path, expected_hash);
            Err("La descarga está preparada, pero macOS necesita el adaptador de máquina virtual Virtualization.framework antes de poder instalar y arrancar una distribución.".into())
        }
    })();

    if let Err(error) = &install_result {
        let _ = manager.set_status(
            &distro_id,
            distroboxer::InstallStatus::Failed,
            Some(error.clone()),
        );
    }
    install_result
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            list_distributions,
            install_distribution,
            start_terminal,
            write_terminal,
            resize_terminal,
            stop_terminal,
            list_terminal_sessions
        ])
        .run(tauri::generate_context!())
        .expect("failed to run DistroBoxer Desktop");
}
 
#[cfg(test)]
mod download_url_tests {
    use super::*;

    #[test]
    fn trusts_official_image_server_mirrors_only_over_https() {
        for value in [
            "https://images.linuxcontainers.org/rootfs.tar.xz",
            "https://ca.images.linuxcontainers.org/rootfs.tar.xz",
            "https://us.images.linuxcontainers.org/rootfs.tar.xz",
            "https://dl-cdn.alpinelinux.org/rootfs.tar.gz",
        ] {
            assert!(is_trusted_download_url(&reqwest::Url::parse(value).unwrap()), "{value}");
        }
    }

    #[test]
    fn rejects_lookalike_mirrors_and_plain_http() {
        for value in [
            "https://images.linuxcontainers.org.evil.example/rootfs.tar.xz",
            "https://evilimages.linuxcontainers.org/rootfs.tar.xz",
            "http://ca.images.linuxcontainers.org/rootfs.tar.xz",
            "https://example.com/rootfs.tar.xz",
        ] {
            assert!(!is_trusted_download_url(&reqwest::Url::parse(value).unwrap()), "{value}");
        }
    }
}
