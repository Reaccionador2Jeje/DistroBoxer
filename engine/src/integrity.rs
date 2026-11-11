//! Integrity checks for files downloaded by future installers.

use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

/// Computes the lowercase SHA-256 digest of a file.
pub fn sha256_file(path: impl AsRef<Path>) -> io::Result<String> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 32 * 1024];

    loop {
        let bytes_read = file.read(&mut buffer)?;
        if bytes_read == 0 {
            break;
        }
        hasher.update(&buffer[..bytes_read]);
    }

    Ok(encode_hex(&hasher.finalize()))
}

/// Verifies a file against a trusted, expected SHA-256 hex digest.
///
/// The expected digest must contain exactly 64 hexadecimal characters.
pub fn verify_sha256(path: impl AsRef<Path>, expected: &str) -> io::Result<bool> {
    if expected.len() != 64 || !expected.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "expected SHA-256 must be exactly 64 hexadecimal characters",
        ));
    }

    let actual = sha256_file(path)?;
    Ok(actual.eq_ignore_ascii_case(expected))
}

fn encode_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);

    for byte in bytes {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }

    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_file() -> std::path::PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock should be valid")
            .as_nanos();
        std::env::temp_dir().join(format!("distroboxer-sha256-{nonce}"))
    }

    #[test]
    fn verifies_known_sha256_digest() {
        let path = temp_file();
        fs::write(&path, b"abc").unwrap();

        assert_eq!(
            sha256_file(&path).unwrap(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert!(verify_sha256(
            &path,
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        )
        .unwrap());

        fs::remove_file(path).unwrap();
    }

    #[test]
    fn rejects_malformed_expected_digest() {
        let path = temp_file();
        fs::write(&path, b"abc").unwrap();

        assert_eq!(
            verify_sha256(&path, "not-a-digest").unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );

        fs::remove_file(path).unwrap();
    }

    #[test]
    fn detects_digest_mismatch() {
        let path = temp_file();
        fs::write(&path, b"abc").unwrap();

        assert!(!verify_sha256(&path, &"0".repeat(64)).unwrap());

        fs::remove_file(path).unwrap();
    }
}
