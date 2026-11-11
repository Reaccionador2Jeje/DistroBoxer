use portable_pty::{native_pty_system, CommandBuilder, PtySize};
use serde::Serialize;
use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::Duration;
use tauri::{Emitter, Manager};

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
    #[cfg(not(target_os = "linux"))]
    {
        Err("La terminal PRoot integrada está disponible inicialmente en Linux".into())
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
                        code: status.exit_code(),
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

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            list_distributions,
            start_terminal,
            write_terminal,
            resize_terminal,
            stop_terminal,
            list_terminal_sessions
        ])
        .run(tauri::generate_context!())
        .expect("failed to run DistroBoxer Desktop");
}
