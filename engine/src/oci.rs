//! Verified OCI image layer application for Linux root filesystems.
//!
//! The Android downloader resolves a registry manifest and stores each blob
//! under a generated filename. This module verifies every layer digest,
//! applies layers in order, and honours OCI whiteouts without escaping rootfs.

use crate::{
    installer::{apply_deferred_directory_modes, extract_tar_with_progress_deferred_directories},
    sha256_file,
};
use flate2::read::GzDecoder;
use serde::Deserialize;
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LayerManifest {
    schema_version: u32,
    layers: Vec<LayerDescriptor>,
}

#[derive(Debug, Deserialize)]
struct LayerDescriptor {
    digest: String,
    media_type: String,
    file: String,
}

/// Applies a downloaded OCI image manifest and its verified layer blobs.
///
/// The manifest must reference generated files located beside it. Layer
/// digests are checked again here, even if the downloader already checked them.
pub fn extract_oci_layers_with_progress(
    manifest_path: impl AsRef<Path>,
    destination: impl AsRef<Path>,
    mut progress: impl FnMut(String),
) -> io::Result<()> {
    let manifest_path = manifest_path.as_ref();
    let manifest_meta = fs::symlink_metadata(manifest_path)?;
    if !manifest_meta.is_file() || manifest_meta.file_type().is_symlink() {
        return Err(invalid("OCI manifest must be a regular file"));
    }
    let manifest: LayerManifest = serde_json::from_slice(&fs::read(manifest_path)?)
        .map_err(|error| invalid(&format!("invalid OCI layer manifest: {error}")))?;
    if manifest.schema_version != 1 || manifest.layers.is_empty() || manifest.layers.len() > 128 {
        return Err(invalid("unsupported or empty OCI manifest"));
    }

    fs::create_dir_all(destination.as_ref())?;
    let root = fs::canonicalize(destination.as_ref())?;
    let downloads = fs::canonicalize(
        manifest_path
            .parent()
            .ok_or_else(|| invalid("manifest has no parent"))?,
    )?;

    let mut total_unpacked_bytes = 0_u64;
    let mut total_entries = 0_usize;
    // Do not apply restrictive directory modes between OCI layers: later
    // layers may still need to create or replace entries inside those paths.
    let mut directory_modes = HashMap::new();
    for (index, layer) in manifest.layers.iter().enumerate() {
        if !layer.file.starts_with("layer-")
            || !layer.file.ends_with(".blob")
            || layer.file.contains('/')
            || layer.file.contains('\\')
        {
            return Err(invalid(
                "OCI layer filename is not a generated safe filename",
            ));
        }
        if !layer.digest.starts_with("sha256:") || layer.digest.len() != 71 {
            return Err(invalid("OCI layer uses an unsupported digest"));
        }
        let expected = &layer.digest[7..];
        if !expected.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err(invalid("OCI layer digest is malformed"));
        }

        let blob = downloads.join(&layer.file);
        let metadata = fs::symlink_metadata(&blob)?;
        if !metadata.is_file() || metadata.file_type().is_symlink() {
            return Err(invalid("OCI layer blob must be a regular file"));
        }
        if sha256_file(&blob)?.eq_ignore_ascii_case(expected) == false {
            return Err(invalid("OCI layer SHA-256 verification failed"));
        }

        progress(format!(
            "Aplicando capa OCI {}/{}…",
            index + 1,
            manifest.layers.len()
        ));
        let remaining_bytes = MAX_OCI_UNPACKED_BYTES.saturating_sub(total_unpacked_bytes);
        let remaining_entries = MAX_OCI_ENTRIES.saturating_sub(total_entries);
        let (layer_bytes, layer_entries) = apply_layer(
            &blob,
            &layer.media_type,
            &root,
            remaining_bytes,
            remaining_entries,
            &mut progress,
            &mut directory_modes,
        )?;
        total_unpacked_bytes += layer_bytes;
        total_entries += layer_entries;
    }
    apply_deferred_directory_modes(&root, directory_modes)?;
    progress(format!("Se aplicaron {} capas OCI.", manifest.layers.len()));
    Ok(())
}

fn apply_layer(
    blob: &Path,
    media_type: &str,
    root: &Path,
    max_unpacked_bytes: u64,
    max_entries: usize,
    progress: &mut dyn FnMut(String),
    directory_modes: &mut HashMap<PathBuf, u32>,
) -> io::Result<(u64, usize)> {
    let compressed = match media_type {
        "application/vnd.oci.image.layer.v1.tar+gzip"
        | "application/vnd.docker.image.rootfs.diff.tar.gzip" => true,
        "application/vnd.oci.image.layer.v1.tar"
        | "application/vnd.docker.image.rootfs.diff.tar" => false,
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                format!("unsupported OCI layer media type: {media_type}"),
            ))
        }
    };

    let (whiteouts, uncompressed_bytes, entry_count) =
        collect_whiteouts(blob, compressed, max_unpacked_bytes, max_entries)?;
    for marker in &whiteouts {
        apply_whiteout(root, marker)?;
    }

    let file = File::open(blob)?;
    if compressed {
        extract_tar_with_progress_deferred_directories(
            GzDecoder::new(file),
            root,
            progress,
            directory_modes,
        )?;
    } else {
        extract_tar_with_progress_deferred_directories(file, root, progress, directory_modes)?;
    }

    // OCI marker files are metadata, not part of the resulting rootfs.
    for marker in whiteouts {
        remove_inside_root(root, &marker)?;
    }
    Ok((uncompressed_bytes, entry_count))
}

fn collect_whiteouts(
    blob: &Path,
    compressed: bool,
    max_unpacked_bytes: u64,
    max_entries: usize,
) -> io::Result<(Vec<PathBuf>, u64, usize)> {
    let file = File::open(blob)?;
    let reader: Box<dyn Read> = if compressed {
        Box::new(GzDecoder::new(file))
    } else {
        Box::new(file)
    };
    let mut archive = tar::Archive::new(reader);
    let mut markers = Vec::new();
    let mut uncompressed_bytes = 0_u64;
    let mut entry_count = 0_usize;
    for item in archive.entries()? {
        let entry = item?;
        entry_count += 1;
        if entry_count > MAX_LAYER_ENTRIES || entry_count > max_entries {
            return Err(invalid("OCI layer contains too many entries"));
        }
        uncompressed_bytes = uncompressed_bytes
            .checked_add(entry.header().size()?)
            .filter(|bytes| *bytes <= max_unpacked_bytes && *bytes <= MAX_OCI_UNPACKED_BYTES)
            .ok_or_else(|| invalid("OCI layer exceeds the uncompressed size limit"))?;
        let path = entry.path()?.into_owned();
        validate_relative(&path)?;
        if path
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.starts_with(".wh."))
        {
            markers.push(path);
        }
    }
    Ok((markers, uncompressed_bytes, entry_count))
}

fn apply_whiteout(root: &Path, marker: &Path) -> io::Result<()> {
    let name = marker
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or_else(|| invalid("invalid whiteout name"))?;
    let parent = marker.parent().unwrap_or_else(|| Path::new(""));
    if name == ".wh..wh..opq" {
        let Some(directory) = resolve_existing_directory(root, parent)? else {
            return Ok(());
        };
        for entry in fs::read_dir(directory)? {
            let entry = entry?;
            remove_entry_without_following_symlinks(&entry.path())?;
        }
        return Ok(());
    }

    let target_name = name
        .strip_prefix(".wh.")
        .ok_or_else(|| invalid("invalid whiteout marker"))?;
    if target_name.is_empty() {
        return Err(invalid("empty OCI whiteout target"));
    }
    let target = parent.join(target_name);
    remove_inside_root(root, &target)
}

fn remove_inside_root(root: &Path, relative: &Path) -> io::Result<()> {
    validate_relative(relative)?;
    let Some(parent) = relative.parent() else {
        return Err(invalid("OCI removal target has no parent"));
    };
    let Some(parent_dir) = resolve_existing_directory(root, parent)? else {
        return Ok(());
    };
    let name = relative
        .file_name()
        .ok_or_else(|| invalid("invalid OCI removal target"))?;
    remove_entry_without_following_symlinks(&parent_dir.join(name))
}

fn resolve_existing_directory(root: &Path, relative: &Path) -> io::Result<Option<PathBuf>> {
    if relative.as_os_str().is_empty() {
        return Ok(Some(root.to_path_buf()));
    }
    validate_relative(relative)?;
    let mut current = root.to_path_buf();
    for component in relative.components() {
        let Component::Normal(name) = component else {
            continue;
        };
        current.push(name);
        match fs::symlink_metadata(&current) {
            Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => {}
            Ok(_) => return Err(invalid("OCI whiteout parent is not a real directory")),
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error),
        }
    }
    Ok(Some(current))
}

fn remove_entry_without_following_symlinks(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => fs::remove_dir_all(path),
        Ok(_) => fs::remove_file(path),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error),
    }
}

fn validate_relative(path: &Path) -> io::Result<()> {
    if path.as_os_str().is_empty() {
        return Err(invalid("empty OCI path"));
    }
    for component in path.components() {
        match component {
            Component::Normal(_) | Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(invalid("OCI path escapes the root filesystem"));
            }
        }
    }
    Ok(())
}

const MAX_OCI_UNPACKED_BYTES: u64 = 8 * 1024 * 1024 * 1024;
const MAX_LAYER_ENTRIES: usize = 500_000;
const MAX_OCI_ENTRIES: usize = 1_000_000;

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_dir() -> PathBuf {
        let n = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("distroboxer-oci-{n}"))
    }

    fn gzip_layer(entries: &[(&str, &[u8])]) -> Vec<u8> {
        use flate2::write::GzEncoder;
        use flate2::Compression;
        use std::io::Write;
        let mut tar_bytes = tar::Builder::new(Vec::new());
        for (path, contents) in entries {
            let mut header = tar::Header::new_gnu();
            header.set_size(contents.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            tar_bytes.append_data(&mut header, path, *contents).unwrap();
        }
        let tar_bytes = tar_bytes.into_inner().unwrap();
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&tar_bytes).unwrap();
        encoder.finish().unwrap()
    }

    #[test]
    fn applies_layers_and_honours_root_level_whiteouts() {
        let base = temp_dir();
        let downloads = base.join("downloads");
        let rootfs = base.join("rootfs");
        fs::create_dir_all(&downloads).unwrap();

        let first = gzip_layer(&[("remove-me", b"old"), ("etc/os-release", b"ID=debian\n")]);
        let second = gzip_layer(&[(".wh.remove-me", b"")]);
        fs::write(downloads.join("layer-000.blob"), &first).unwrap();
        fs::write(downloads.join("layer-001.blob"), &second).unwrap();

        let digest0 = crate::sha256_file(downloads.join("layer-000.blob")).unwrap();
        let digest1 = crate::sha256_file(downloads.join("layer-001.blob")).unwrap();
        let manifest = serde_json::json!({
            "schemaVersion": 1,
            "layers": [
                {"digest": format!("sha256:{digest0}"), "media_type": "application/vnd.oci.image.layer.v1.tar+gzip", "file": "layer-000.blob"},
                {"digest": format!("sha256:{digest1}"), "media_type": "application/vnd.oci.image.layer.v1.tar+gzip", "file": "layer-001.blob"}
            ]
        });
        let manifest_path = downloads.join("manifest.json");
        fs::write(&manifest_path, serde_json::to_vec(&manifest).unwrap()).unwrap();

        extract_oci_layers_with_progress(&manifest_path, &rootfs, |_| {}).unwrap();
        assert!(!rootfs.join("remove-me").exists());
        assert_eq!(
            fs::read_to_string(rootfs.join("etc/os-release")).unwrap(),
            "ID=debian\n"
        );
        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn rejects_parent_paths_in_whiteouts() {
        assert!(validate_relative(Path::new("../outside")).is_err());
    }

    #[test]
    fn whiteout_removes_file_without_following_symlinks() {
        let root = temp_dir();
        fs::create_dir_all(root.join("etc")).unwrap();
        fs::write(root.join("etc/old.conf"), b"old").unwrap();
        apply_whiteout(&root, Path::new("etc/.wh.old.conf")).unwrap();
        assert!(!root.join("etc/old.conf").exists());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn opaque_whiteout_clears_directory_contents() {
        let root = temp_dir();
        fs::create_dir_all(root.join("var/cache")).unwrap();
        fs::write(root.join("var/cache/old"), b"old").unwrap();
        apply_whiteout(&root, Path::new("var/cache/.wh..wh..opq")).unwrap();
        assert_eq!(fs::read_dir(root.join("var/cache")).unwrap().count(), 0);
        fs::remove_dir_all(root).unwrap();
    }
}
