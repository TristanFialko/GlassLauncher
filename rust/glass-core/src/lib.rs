//! Glass Launcher core: platform-independent logic shared by the Android JNI
//! layer (`glass-jni`) and host tooling (`glass-cli`).
//!
//! Everything here is pure Rust with no dependencies and no `unsafe`.

#![forbid(unsafe_code)]

pub mod search;
pub mod snapshot;

/// Crate version, reported through the JNI health check.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// Bumped whenever the JNI/bridge contract changes shape so Java can refuse a
/// mismatched native library instead of misbehaving.
/// 2: `nativePrepareSearch` (search snapshot preparation).
pub const ABI_VERSION: u32 = 2;

/// Runs a tiny end-to-end search over a fixed index and a fixed snapshot. Used
/// as the native health check after the library is loaded on a device.
pub fn self_test() -> bool {
    let idx = search::SearchIndex::from_wire(
        "app\u{1f}YouTube\u{1e}shortcut\u{1f}Wi-Fi\u{1f}wifi\u{1f}network\u{1e}app\u{1f}Netflix",
    );
    let snap = snapshot::prepare(
        "glass-snapshot\u{1f}1\u{1e}i\u{1f}com.google.android.youtube.tv\u{1f}YouTube\u{1f}1\u{1e}\
         i\u{1f}com.samsung.x\u{1f}Hidden\u{1f}1\u{1e}i\u{1f}com.android.settings\u{1f}Settings\u{1f}1\u{1e}\
         e\u{1f}shortcut\u{1f}1\u{1f}com.android.settings\u{1f}Settings\u{1e}e\u{1f}shortcut\u{1f}1\u{1f}\u{1f}Wi-Fi\u{1f}wifi",
    );
    idx.len() == 3
        && idx.query("you", 24) == vec![0]
        && idx.query("netw", 24) == vec![1]
        && idx.query("", 24).is_empty()
        && matches!(snap, Ok(ref p) if p.refs == [0, 2, 4] && p.index.query("wifi", 24) == vec![2])
}

#[cfg(test)]
mod tests {
    #[test]
    fn self_test_passes() {
        assert!(super::self_test());
    }
}
