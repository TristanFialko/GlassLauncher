package com.glasslauncher

/** An installed, launchable package as AppDiscovery reports it (id = package name). */
data class InstalledApp(val id: String, val name: String, val show: Boolean = true)

/** The key sounds keyboard.js plays (G.sound.key). */
enum class KeyKind { ALPHA, SPACE, ALT, ENTER }

/** What activating a result does. The host executes it; see docs/nativeui/SEARCH.md for the JS each one mirrors. */
sealed class SearchAction {
    /** registry.js appEntry.run: G.launch({name,id}). Search stays open; the host records Recently Used on success. */
    data class LaunchApp(val id: String, val name: String) : SearchAction()
    /** shortcuts.js set:*: G.openSettings(kind); kind is one of SearchCatalog.SETTINGS_KINDS. Search stays open. */
    data class OpenSettings(val kind: String) : SearchAction()
    /** glass:cc / glass:volume: Search closes without restoring focus, then Control Centre opens (focus the volume row for glass:volume). */
    data class OpenControlCentre(val focusVolume: Boolean) : SearchAction()
    /** glass:theme: G.theme.toggle() then a toast "Dark appearance"/"Light appearance". Search stays open. */
    object ToggleTheme : SearchAction()
    /** Empty-query suggestion: replaces the query with [text] and focuses the keyboard. Handled inside Search. */
    data class UseSuggestion(val text: String) : SearchAction()
}

enum class ResultKind { APP, SHORTCUT, SUGGEST }

/** One row of the results list. [icon] is a built-in icon key (see SvgIcons); apps use [pkg] for the real icon. */
class SearchResult(
    val kind: ResultKind,
    val title: String,
    val subtitle: String,
    val tag: String,
    val icon: String,
    val pkg: String?,
    val action: SearchAction,
)

/** A non-app registry entry (shortcuts and any extra provider), mirroring registry.js `entry`. */
class CatalogEntry(
    val id: String,
    val kind: String,                      // "app" | "shortcut" | anything else (rejected by Rust, as in the WebView)
    val title: String,
    val subtitle: String,
    val keywords: List<String>,
    val tag: String,
    val icon: String,
    val supersededBy: List<String> = emptyList(),
    val available: Boolean = true,
    val action: SearchAction,
)

/** G.initials: first letters of the first two words, upper-cased; "?" when there is no name. */
object SearchInitials {
    fun of(name: String?): String {
        val w = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
        if (w.isEmpty()) return "?"
        return (w[0].substring(0, 1) + (if (w.size > 1) w[1].substring(0, 1) else "")).uppercase()
    }
}
