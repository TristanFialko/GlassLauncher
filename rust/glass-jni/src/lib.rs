//! JNI entry points for `com.glasslauncher.RustCore`.
//!
//! Contract (see docs/architecture/TARGET_ARCHITECTURE.md):
//! * Strings cross the boundary as UTF-8 `byte[]`, never `jstring`, so there is
//!   no modified-UTF-8 handling and no per-character JNI work.
//! * Every entry point is wrapped in `catch_unwind`; a panic becomes an error
//!   value (`-1` / `null`), never an unwind into the JVM.
//! * No native thread is ever created and no Java callback is made, so no lock
//!   is held across a call into Android.
//! * State is one process-wide search index behind a `Mutex` (set by
//!   `nativeSetSearchIndex` or `nativePrepareSearch`); replacing it is
//!   idempotent, so activity recreation needs no native cleanup.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::sync::Mutex;

use glass_core::search::SearchIndex;
use glass_core::snapshot::prepare;
use jni_sys::{jbyte, jbyteArray, jclass, jint, jsize, JNIEnv};

static INDEX: Mutex<Option<SearchIndex>> = Mutex::new(None);

/// Upper bound for any byte[] accepted from Java (a search index for a few
/// hundred apps is ~20 KB); larger input is rejected rather than allocated.
const MAX_INPUT_BYTES: jsize = 4 * 1024 * 1024;

const ERR_PANIC: jint = -1;
const ERR_INPUT: jint = -2;

/// Copies a Java byte[] into a Rust Vec. Returns None for null or oversized arrays.
///
/// # Safety
/// `env` must be the valid JNIEnv for the current thread and `arr` a local
/// reference (or null) obtained from that env.
unsafe fn read_bytes(env: *mut JNIEnv, arr: jbyteArray) -> Option<Vec<u8>> {
    if arr.is_null() {
        return None;
    }
    let f = &**env;
    let len = (f.GetArrayLength?)(env, arr);
    if !(0..=MAX_INPUT_BYTES).contains(&len) {
        return None;
    }
    let mut buf = vec![0u8; len as usize];
    (f.GetByteArrayRegion?)(env, arr, 0, len, buf.as_mut_ptr() as *mut jbyte);
    if (f.ExceptionCheck?)(env) != 0 {
        return None;
    }
    Some(buf)
}

/// Allocates a new Java byte[] holding `data`. Returns null on failure (a
/// pending OutOfMemoryError is then thrown in Java, which RustCore catches).
///
/// # Safety
/// `env` must be the valid JNIEnv for the current thread.
unsafe fn new_bytes(env: *mut JNIEnv, data: &[u8]) -> jbyteArray {
    let f = &**env;
    let (Some(new_arr), Some(set_region)) = (f.NewByteArray, f.SetByteArrayRegion) else {
        return ptr::null_mut();
    };
    let Ok(len) = jsize::try_from(data.len()) else {
        return ptr::null_mut();
    };
    let arr = new_arr(env, len);
    if arr.is_null() {
        return ptr::null_mut();
    }
    set_region(env, arr, 0, len, data.as_ptr() as *const jbyte);
    arr
}

fn guard<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(fallback)
}

/// `static native byte[] nativeVersion()` → `"<crate version>;<abi version>"`.
/// # Safety
/// Called only by the JVM with a valid `JNIEnv` for the current thread.
#[no_mangle]
pub unsafe extern "system" fn Java_com_glasslauncher_RustCore_nativeVersion(
    env: *mut JNIEnv,
    _class: jclass,
) -> jbyteArray {
    guard(ptr::null_mut(), || {
        let v = format!("{};{}", glass_core::VERSION, glass_core::ABI_VERSION);
        // SAFETY: env is supplied by the JVM for this thread.
        unsafe { new_bytes(env, v.as_bytes()) }
    })
}

/// `static native boolean nativeSelfTest()`.
/// # Safety
/// Called only by the JVM with a valid `JNIEnv` for the current thread.
#[no_mangle]
pub unsafe extern "system" fn Java_com_glasslauncher_RustCore_nativeSelfTest(
    _env: *mut JNIEnv,
    _class: jclass,
) -> u8 {
    guard(0, || u8::from(glass_core::self_test()))
}

/// `static native int nativeSetSearchIndex(byte[] wireUtf8)` → entry count, or a negative error.
/// # Safety
/// Called only by the JVM with a valid `JNIEnv` for the current thread.
#[no_mangle]
pub unsafe extern "system" fn Java_com_glasslauncher_RustCore_nativeSetSearchIndex(
    env: *mut JNIEnv,
    _class: jclass,
    wire: jbyteArray,
) -> jint {
    guard(ERR_PANIC, || {
        // SAFETY: env/wire come straight from the JVM for this call.
        let Some(bytes) = (unsafe { read_bytes(env, wire) }) else {
            return ERR_INPUT;
        };
        let Ok(text) = String::from_utf8(bytes) else {
            return ERR_INPUT;
        };
        // Build outside the lock; only the pointer swap happens while holding it.
        let idx = SearchIndex::from_wire(&text);
        let n = idx.len() as jint;
        let mut slot = INDEX.lock().unwrap_or_else(|p| p.into_inner());
        *slot = Some(idx);
        n
    })
}

/// `static native byte[] nativeSearch(byte[] queryUtf8, int max)` →
/// comma-separated entry indices (UTF-8), or null if no index / bad input.
/// # Safety
/// Called only by the JVM with a valid `JNIEnv` for the current thread.
#[no_mangle]
pub unsafe extern "system" fn Java_com_glasslauncher_RustCore_nativeSearch(
    env: *mut JNIEnv,
    _class: jclass,
    query: jbyteArray,
    max: jint,
) -> jbyteArray {
    guard(ptr::null_mut(), || {
        // SAFETY: env/query come straight from the JVM for this call.
        let Some(bytes) = (unsafe { read_bytes(env, query) }) else {
            return ptr::null_mut();
        };
        let Ok(text) = String::from_utf8(bytes) else {
            return ptr::null_mut();
        };
        let hits = {
            let slot = INDEX.lock().unwrap_or_else(|p| p.into_inner());
            match slot.as_ref() {
                Some(idx) => idx.query(&text, usize::try_from(max).unwrap_or(0)),
                None => return ptr::null_mut(),
            }
        };
        let mut out = String::with_capacity(hits.len() * 4);
        for (n, i) in hits.iter().enumerate() {
            if n > 0 {
                out.push(',');
            }
            out.push_str(&i.to_string());
        }
        // SAFETY: env is supplied by the JVM for this thread; the lock is released.
        unsafe { new_bytes(env, out.as_bytes()) }
    })
}

/// `static native byte[] nativePrepareSearch(byte[] snapshotUtf8)` → refs of
/// the kept entries (comma-separated snapshot record numbers, UTF-8), or null
/// if the snapshot is malformed/unsupported (JavaScript then stays on its own
/// path). On success the prepared index replaces the current one.
/// # Safety
/// Called only by the JVM with a valid `JNIEnv` for the current thread.
#[no_mangle]
pub unsafe extern "system" fn Java_com_glasslauncher_RustCore_nativePrepareSearch(
    env: *mut JNIEnv,
    _class: jclass,
    snapshot: jbyteArray,
) -> jbyteArray {
    guard(ptr::null_mut(), || {
        // SAFETY: env/snapshot come straight from the JVM for this call.
        let Some(bytes) = (unsafe { read_bytes(env, snapshot) }) else {
            return ptr::null_mut();
        };
        let Ok(text) = String::from_utf8(bytes) else {
            return ptr::null_mut();
        };
        // Prepared outside the lock; only the swap happens while holding it.
        let Ok(prepared) = prepare(&text) else {
            return ptr::null_mut();
        };
        let refs = prepared.refs_wire();
        {
            let mut slot = INDEX.lock().unwrap_or_else(|p| p.into_inner());
            *slot = Some(prepared.index);
        }
        // SAFETY: env is supplied by the JVM for this thread; the lock is released.
        unsafe { new_bytes(env, refs.as_bytes()) }
    })
}
