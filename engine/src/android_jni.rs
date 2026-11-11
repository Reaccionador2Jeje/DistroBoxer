//! JNI entry points used by the Android application.

use jni::objects::JObject;
use jni::sys::jstring;
use jni::JNIEnv;

use crate::supported_distros;

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
            })
        })
        .collect();

    let payload = serde_json::to_string(&catalog).unwrap_or_else(|_| "[]".to_owned());

    match env.new_string(payload) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
