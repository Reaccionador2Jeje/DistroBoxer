//! Safe extraction primitives for verified Linux root filesystem archives.

use flate2::read::GzDecoder;
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::{Component, Path, PathBuf};

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
        if entry_type.is_symlink() {
            let target = entry.link_name()?.ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidData, "symbolic link has no target")
            })?;
            validate_symlink_target(&entry_path, &target)?;
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

        if !entry.unpack_in(&destination)? {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "archive entry would escape the extraction directory",
            ));
        }
    }

    Ok(())
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
    fn rejects_parent_directory_entries() {
        let destination = temp_dir();
        let outside = destination
            .parent()
            .unwrap()
            .join("distroboxer-escape-test");
        let result = extract_tar(
            Cursor::new(tar_with_file("../distroboxer-escape-test")),
            &destination,
        );
        assert_eq!(result.unwrap_err().kind(), io::ErrorKind::InvalidData);
        assert!(!outside.exists());
        if destination.exists() {
            fs::remove_dir_all(destination).unwrap();
        }
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
