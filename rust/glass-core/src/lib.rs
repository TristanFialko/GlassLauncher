//! Glass Launcher core: platform-independent logic shared by the Android JNI
//! layer (`glass-jni`) and host tooling (`glass-cli`).
//!
//! Everything here is pure Rust with no dependencies and no `unsafe`.

#![forbid(unsafe_code)]

pub mod search;

/// Crate version, reported through the JNI health check.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// Bumped whenever the JNI/bridge contract changes shape so Java can refuse a
/// mismatched native library instead of misbehaving.
pub const ABI_VERSION: u32 = 1;

/// Runs a tiny end-to-end search over a fixed index. Used as the native
/// health check after the library is loaded on a device.
pub fn self_test() -> bool {
    let idx = search::SearchIndex::from_wire(
        "app\u{1f}YouTube\u{1e}shortcut\u{1f}Wi-Fi\u{1f}wifi\u{1f}network\u{1e}app\u{1f}Netflix",
    );
    idx.len() == 3
        && idx.query("you", 24) == vec![0]
        && idx.query("netw", 24) == vec![1]
        && idx.query("", 24).is_empty()
}

#[cfg(test)]
mod tests {
    #[test]
    fn self_test_passes() {
        assert!(super::self_test());
    }
}
