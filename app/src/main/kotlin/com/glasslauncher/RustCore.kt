package com.glasslauncher

import android.util.Log

/**
 * Loads the Rust core (libglass.so) once per process and wraps its JNI entry points.
 * Every method degrades to a "not available" result instead of throwing, so a missing, mismatched or broken native
 * library leaves the launcher on its Kotlin fallback paths rather than crashing or crash-looping.
 * Calls are expected off the UI thread; native state is a single process-wide index that is simply replaced, so
 * activity recreation needs no cleanup.
 *
 * The class name stays com.glasslauncher.RustCore and every native method is a static (@JvmStatic) member, so the JNI
 * symbols exported by rust/glass-jni (Java_com_glasslauncher_RustCore_native*, static, jclass receiver) are unchanged.
 */
object RustCore {
    /** Must equal glass_core::ABI_VERSION; a mismatch disables the native path. */
    const val EXPECTED_ABI = 2
    private const val TAG = "GlassRust"

    @Volatile private var loaded = false
    @Volatile private var coreVersion = ""
    @Volatile private var coreStatus = "not loaded"

    init {
        try {
            System.loadLibrary("glass")
            val v = String(nativeVersion() ?: throw IllegalStateException("null version"), Charsets.UTF_8)
            val parts = v.split(';')
            if (parts.size != 2 || parts[1].toInt() != EXPECTED_ABI) {
                coreStatus = "ABI mismatch: $v"
            } else if (!nativeSelfTest()) {
                coreStatus = "self-test failed"
            } else {
                coreVersion = parts[0]
                loaded = true
                coreStatus = "ok"
            }
        } catch (t: Throwable) {
            coreStatus = t.javaClass.simpleName + ": " + t.message
        }
        try {
            Log.i(TAG, "Rust core " + (if (loaded) "v$coreVersion" else "unavailable") + " ($coreStatus)")
        } catch (e: Throwable) {
        }
    }

    @JvmStatic fun isAvailable(): Boolean = loaded
    @JvmStatic fun version(): String = coreVersion
    @JvmStatic fun status(): String = coreStatus

    /** Replaces the search index. Returns the entry count, or a negative value on any failure. */
    @JvmStatic
    fun setSearchIndex(wire: String?): Int {
        if (!loaded || wire == null) return -1
        return try { nativeSetSearchIndex(wire.toByteArray(Charsets.UTF_8)) } catch (t: Throwable) { -1 }
    }

    /**
     * Prepares a search session from a registry snapshot and makes it the current index. Returns the kept entries'
     * snapshot record numbers, comma-separated ("" if none); null if unavailable, malformed or unsupported.
     */
    @JvmStatic
    fun prepareSearch(snapshot: String?): String? {
        if (!loaded || snapshot == null) return null
        return try {
            nativePrepareSearch(snapshot.toByteArray(Charsets.UTF_8))?.let { String(it, Charsets.UTF_8) }
        } catch (t: Throwable) { null }
    }

    /** Comma-separated indices into the last index, best first ("" for no hits); null on any failure. */
    @JvmStatic
    fun search(query: String?, max: Int): String? {
        if (!loaded || query == null) return null
        return try {
            nativeSearch(query.toByteArray(Charsets.UTF_8), max)?.let { String(it, Charsets.UTF_8) }
        } catch (t: Throwable) { null }
    }

    @JvmStatic private external fun nativeVersion(): ByteArray?
    @JvmStatic private external fun nativeSelfTest(): Boolean
    @JvmStatic private external fun nativeSetSearchIndex(wireUtf8: ByteArray): Int
    @JvmStatic private external fun nativeSearch(queryUtf8: ByteArray, max: Int): ByteArray?
    @JvmStatic private external fun nativePrepareSearch(snapshotUtf8: ByteArray): ByteArray?
}
