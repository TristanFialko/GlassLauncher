package com.glasslauncher

import org.json.JSONArray

/**
 * Production [SearchFactsSource] for native Search; called only on the engine's executor. Outside NativeHomeActivity so the wiring
 * of the fact policy (docs/nativeui/SEARCH.md "Fact policy") is JVM-testable.
 *
 * [installedJson] must throw on failure (`AppDiscovery.installedAppsJsonOrThrow`), never return `[]` for a broken PackageManager.
 * [settingsAvailable] must throw on an internal error (`SettingsLauncher.hasOrThrow`), never answer `false`: SearchEngine caches
 * real answers for the engine, so a swallowed error would hide that shortcut for the engine's lifetime instead of one session.
 */
class NativeSearchFacts(
    private val installedJson: () -> String,
    private val settingsAvailable: (String) -> Boolean,
) : SearchFactsSource {
    // Essential fact: a failure must reach SearchEngine (explicit failure, retried on the next open), not become "no apps".
    override fun installedApps(): List<InstalledApp> {
        val a = JSONArray(installedJson())
        return List(a.length()) { i -> a.getJSONObject(i).let { InstalledApp(it.getString("id"), it.optString("name"), it.optBoolean("show", true)) } }
    }

    override fun aliases(): Map<String, List<String>> = emptyMap()   // window.SEARCH_ALIASES is not configured in apps.js

    override fun hasSettings(kind: String): Boolean = settingsAvailable(kind)
}
