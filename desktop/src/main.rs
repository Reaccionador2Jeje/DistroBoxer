use serde::Serialize;

#[derive(Serialize)]
struct DistroEntry {
    id: &'static str,
    name: &'static str,
    package_manager: &'static str,
    architectures: &'static [&'static str],
    homepage: &'static str,
    bootstrap: &'static str,
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
        })
        .collect()
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![list_distributions])
        .run(tauri::generate_context!())
        .expect("failed to run DistroBoxer Desktop");
}
