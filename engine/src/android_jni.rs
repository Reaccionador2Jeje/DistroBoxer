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
    listener: JObject,
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

    let result = StorageManager::new(base_directory).install_tar_gz_with_progress(
        &distro_id,
        archive_path,
        &expected_sha256,
        |line| {
            if let Ok(message) = env.new_string(line) {
                let message_object: JObject = message.into();
                let _ = env.call_method(
                    &listener,
                    "onInstallLog",
                    "(Ljava/lang/String;)V",
                    &[jni::objects::JValue::Object(&message_object)],
                );
            }
        },
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


/// Verifies and installs a rootfs archive using its declared compression format.
#[no_mangle]
pub extern "system" fn Java_com_atdevdev_distroboxer_NativeBridge_nativeInstallArchive(
    mut env: JNIEnv,
    _this: JObject,
    distro_id: JString,
    base_directory: JString,
    archive_path: JString,
    archive_format: JString,
    expected_sha256: JString,
    listener: JObject,
) -> jstring {
    let read_string = |env: &mut JNIEnv, value: JString| -> Option<String> {
        env.get_string(&value).ok().map(Into::into)
    };
    let distro_id = match read_string(&mut env, distro_id) {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };
    let base_directory = match read_string(&mut env, base_directory) {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };
    let archive_path = match read_string(&mut env, archive_path) {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };
    let archive_format = match read_string(&mut env, archive_format) {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };
    let expected_sha256 = match read_string(&mut env, expected_sha256) {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };

    let result = StorageManager::new(base_directory).install_archive_with_progress(
        &distro_id,
        archive_path,
        &archive_format,
        &expected_sha256,
        |line| {
            if let Ok(message) = env.new_string(line) {
                let message_object: JObject = message.into();
                let _ = env.call_method(
                    &listener,
                    "onInstallLog",
                    "(Ljava/lang/String;)V",
                    &[jni::objects::JValue::Object(&message_object)],
                );
            }
        },
    );
    let message = match result {
        Ok(()) => String::new(),
        Err(error) => error.to_string(),
    };

    match env.new_string(message) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
