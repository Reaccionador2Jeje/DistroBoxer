//! Safe extraction primitives for verified Linux root filesystem archives.

use flate2::read::GzDecoder;
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::{Component, Path};

/// Extracts a gzip-compressed tar archive into an existing app-private directory.
///
/// The caller must verify the archive checksum against trusted distribution
/// metadata before invoking this function.
pub fn extract_tar_gz(
    archive_path: impl AsRef<Path>,
    destination: impl AsRef<Path>,
) -> io::Result<()> {
    let archive_path = archive_path.as_ref();
    let file_metadata = fs::symlink_metadata(archive_path)?;
    if !file_metadata.is_file() || file_metadata.file_type().is_symlink() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "archive must be a regular file, not a symbolic link",
        ));
    }

    let file = File::open(archive_path)?;
    extract_tar(GzDecoder::new(file), destination)
}

/// Extracts an uncompressed tar stream after validating every path and link.
pub fn extract_tar<R: Read>(reader: R, destination: impl AsRef<Path>) -> io::Result<()> {
    fs::create_dir_all(destination.as_ref())?;
    let destination = fs::canonicalize(destination)?;

    let mut archive = tar::Archive::new(reader);
    let mut entry_count = 0_usize;
    let mut total_unpacked_bytes = 0_u64;
    // Absolute symlinks are normal in Linux rootfs archives. Defer their
    // creation until every regular entry is unpacked, then rewrite them as
    // equivalent relative links so they cannot escape the host app directory.
    let mut deferred_symlinks: Vec<(std::path::PathBuf, std::path::PathBuf)> = Vec::new();
    archive.set_preserve_permissions(false);
    archive.set_preserve_mtime(false);
    archive.set_unpack_xattrs(false);

    for item in archive.entries()? {
        let mut entry = item?;
        entry_count += 1;
        if entry_count > MAX_ARCHIVE_ENTRIES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "archive contains too many entries",
            ));
        }
        total_unpacked_bytes = total_unpacked_bytes
            .checked_add(entry.header().size()?)
            .filter(|size| *size <= MAX_UNPACKED_BYTES)
            .ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    "archive exceeds the uncompressed size limit",
                )
            })?;

        let entry_path = entry.path()?.into_owned();
        validate_archive_path(&entry_path)?;

        let entry_type = entry.header().entry_type();
        // Android's app sandbox cannot recreate device nodes or FIFOs. PRoot
        // provides runtime /dev bindings, so safely skip these archive entries.
        if entry_type.is_block_special()
            || entry_type.is_character_special()
            || entry_type.is_fifo()
        {
            continue;
        }
        if entry_type.is_symlink() {
            let target = entry.link_name()?.ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidData, "symbolic link has no target")
            })?;
            let safe_target = if target.is_absolute() {
                absolute_link_as_relative(&entry_path, &target)?
            } else {
                validate_symlink_target(&entry_path, &target)?;
                target
            };
            deferred_symlinks.push((entry_path, safe_target));
            continue;
        } else if entry_type.is_hard_link() {
            let target = entry.link_name()?.ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidData, "hard link has no target")
            })?;
            validate_archive_path(&target)?;
        } else if !entry_type.is_file() && !entry_type.is_dir() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "unsupported special file in root filesystem archive",
            ));
        }

        if !entry.unpack_in(&destination).map_err(|error| {
            io::Error::new(
                error.kind(),
                format!("failed to extract '{}': {error}", entry_path.display()),
            )
        })? {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!(
                    "archive entry '{}' would escape the extraction directory",
                    entry_path.display()
                ),
            ));
        }
    }

    for (link_path, target) in deferred_symlinks {
        let full_link_path = destination.join(&link_path);
        let parent = full_link_path.parent().ok_or_else(invalid_archive_path)?;
        fs::create_dir_all(parent)?;
        std::os::unix::fs::symlink(&target, &full_link_path).map_err(|error| {
            io::Error::new(
                error.kind(),
                format!("failed to create symlink '{}': {error}", link_path.display()),
            )
        })?;
    }

    Ok(())
}

fn absolute_link_as_relative(link_path: &Path, target: &Path) -> io::Result<std::path::PathBuf> {
    let target = target.strip_prefix("/").map_err(|_| invalid_archive_path())?;
    validate_archive_path(target)?;

    let parent = link_path.parent().unwrap_or_else(|| Path::new(""));
    let parent_components: Vec<_> = parent
        .components()
        .filter_map(|component| match component {
            Component::Normal(value) => Some(value.to_os_string()),
            _ => None,
        })
        .collect();
    let target_components: Vec<_> = target
        .components()
        .filter_map(|component| match component {
            Component::Normal(value) => Some(value.to_os_string()),
            _ => None,
        })
        .collect();

    let common = parent_components
        .iter()
        .zip(target_components.iter())
        .take_while(|(left, right)| left == right)
        .count();

    let mut relative = std::path::PathBuf::new();
    for _ in common..parent_components.len() {
        relative.push("..");
    }
    for component in target_components.iter().skip(common) {
        relative.push(component);
    }
    if relative.as_os_str().is_empty() {
        relative.push(".");
    }
    Ok(relative)
}

fn validate_archive_path(path: &Path) -> io::Result<()> {
    if path.as_os_str().is_empty() {
        return Err(invalid_archive_path());
    }

    for component in path.components() {
        match component {
            Component::Normal(_) | Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(invalid_archive_path());
            }
        }
    }

    Ok(())
}

fn validate_symlink_target(link_path: &Path, target: &Path) -> io::Result<()> {
    if target.is_absolute() {
        return Err(invalid_archive_path());
    }

    let mut depth = link_path
        .parent()
        .unwrap_or_else(|| Path::new(""))
        .components()
        .filter(|component| matches!(component, Component::Normal(_)))
        .count() as isize;

    for component in target.components() {
        match component {
            Component::Normal(_) => depth += 1,
            Component::CurDir => {}
            Component::ParentDir => {
                depth -= 1;
                if depth < 0 {
                    return Err(invalid_archive_path());
                }
            }
            Component::RootDir | Component::Prefix(_) => return Err(invalid_archive_path()),
        }
    }

    Ok(())
}

const MAX_ARCHIVE_ENTRIES: usize = 500_000;
const MAX_UNPACKED_BYTES: u64 = 16 * 1024 * 1024 * 1024;

fn invalid_archive_path() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        "archive contains an absolute or path-traversal entry",
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};
    use tar::{Builder, Header};

    fn temp_dir() -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock should be valid")
            .as_nanos();
        std::env::temp_dir().join(format!("distroboxer-extract-{nonce}"))
    }

    fn tar_with_file(path: &str) -> Vec<u8> {
        let mut builder = Builder::new(Vec::new());
        let contents = b"ID=alpine\n";
        let mut header = Header::new_gnu();
        header.set_size(contents.len() as u64);
        header.set_mode(0o644);
        header.set_cksum();
        builder
            .append_data(&mut header, path, &contents[..])
            .unwrap();
        builder.into_inner().unwrap()
    }

    #[test]
    fn extracts_regular_rootfs_files() {
        let destination = temp_dir();
        extract_tar(Cursor::new(tar_with_file("etc/os-release")), &destination).unwrap();
        assert_eq!(
            fs::read_to_string(destination.join("etc/os-release")).unwrap(),
            "ID=alpine\n"
        );
        fs::remove_dir_all(destination).unwrap();
    }

    #[test]
    fn rewrites_absolute_linux_symlinks_to_safe_relative_targets() {
        assert_eq!(
            absolute_link_as_relative(
                Path::new("etc/mtab"),
                Path::new("/proc/self/mounts"),
            )
            .unwrap(),
            Path::new("../proc/self/mounts")
        );
    }

    #[test]
    fn rejects_parent_directory_entries() {
        assert_eq!(
            validate_archive_path(Path::new("../outside"))
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn rejects_absolute_paths() {
        assert_eq!(
            validate_archive_path(Path::new("/etc/passwd"))
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn rejects_symlink_targets_that_escape_root() {
        assert_eq!(
            validate_symlink_target(Path::new("usr/bin/link"), Path::new("../../../../outside"))
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidData
        );
    }
}
