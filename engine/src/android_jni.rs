//! JNI entry points used by the Android application.

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::{supported_distros, StorageManager};

/// Returns the Rust distribution catalog as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_atdevdev_distroboxer_NativeBridge_nativeSupportedDistros(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let catalog: Vec<_> = supported_distros()
        .iter()
        .map(|distro| {
            serde_json::json!({
                "id": distro.id,
                "name": distro.name,
                "package_manager": distro.package_manager,
                "architectures": distro.architectures,
                "homepage": distro.homepage,
                "bootstrap": distro.bootstrap,
                "archive_format": distro.archive_format,
            })
        })
        .collect();

    let payload = serde_json::to_string(&catalog).unwrap_or_else(|_| "[]".to_owned());

    match env.new_string(payload) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Creates the app-private directory layout and metadata for a distribution.
///
/// This does not download or install a Linux root filesystem.
#[no_mangle]
pub extern "system" fn Java_com_atdevdev_distroboxer_NativeBridge_nativePrepareDistro(
    mut env: JNIEnv,
    _this: JObject,
    distro_id: JString,
    base_directory: JString,
) -> jboolean {
    let distro_id: String = match env.get_string(&distro_id) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };
    let base_directory: String = match env.get_string(&base_directory) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };

    match StorageManager::new(base_directory).prepare_distro(&distro_id) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

/// Reports whether a valid root filesystem is present for a distribution.
#[no_mangle]
pub extern "system" fn Java_com_atdevdev_distroboxer_NativeBridge_nativeIsInstalled(
    mut env: JNIEnv,
    _this: JObject,
    distro_id: JString,
    base_directory: JString,
) -> jboolean {
    let distro_id: String = match env.get_string(&distro_id) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };
    let base_directory: String = match env.get_string(&base_directory) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };

    match StorageManager::new(base_directory).is_installed(&distro_id) {
        Ok(true) => JNI_TRUE,
        Ok(false) | Err(_) => JNI_FALSE,
    }
}

/// Verifies and atomically installs a gzip-compressed rootfs tarball.
#[no_mangle]
pub extern "system" fn Java_com_atdevdev_distroboxer_NativeBridge_nativeInstallTarGz(
    mut env: JNIEnv,
    _this: JObject,
    distro_id: JString,
    base_directory: JString,
    archive_path: JString,
    expected_sha256: JString,
) -> jstring {
    let distro_id: String = match env.get_string(&distro_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let base_directory: String = match env.get_string(&base_directory) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let archive_path: String = match env.get_string(&archive_path) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let expected_sha256: String = match env.get_string(&expected_sha256) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let result = StorageManager::new(base_directory).install_tar_gz(
        &distro_id,
        archive_path,
        &expected_sha256,
    );
    let message = match result {
        Ok(()) => String::new(),
        Err(error) => format!("{error:#}"),
    };

    match env.new_string(message) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
