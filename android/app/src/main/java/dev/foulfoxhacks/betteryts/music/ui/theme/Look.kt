package dev.foulfoxhacks.betteryts.music.ui.theme

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.json.JSONArray
import org.json.JSONObject

/** Home sections that can be reordered or hidden, with their labels. */
val HOME_SECTIONS = listOf(
    "speedDial" to "Speed dial",
    "quickPicks" to "Quick picks",
    "stations" to "Your stations",
    "smart" to "Smart playlists",
    "mixes" to "Your mixes",
    "listenAgain" to "Listen again",
    "newReleases" to "New from artists you follow",
    "mixedForYou" to "Mixed for you",
    "playlists" to "Your playlists",
    "favorites" to "Favorites",
)

/** Tabs that can go in the bottom bar (or the side rail on tablets). */
val ALL_TABS = listOf("Home", "Explore", "Search", "Library")

/** Song-menu items that can be hidden (⋮ on any song). */
val MENU_ITEMS = listOf(
    "radio" to "Start radio",
    "playNext" to "Play next",
    "addQueue" to "Add to queue",
    "favorite" to "Add to favorites",
    "playlist" to "Add to playlist…",
    "dislike" to "Dislike",
    "share" to "Share",
    "artist" to "Go to artist",
)

/** Colour tokens the theme editor exposes, with labels. */
val THEME_TOKENS = listOf(
    "ink" to "Background",
    "surface" to "Cards",
    "raised" to "Raised (menus, dialogs)",
    "paper" to "Text",
    "muted" to "Secondary text",
    "accent" to "Accent",
    "eyebrow" to "Labels",
    "hot" to "Warnings",
    "violet" to "Highlight",
    "love" to "Hearts",
)

/** A user-made theme: a base palette with some tokens replaced. */
data class CustomTheme(val name: String, val base: String, val colors: Map<String, Long>) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "betteryts-theme")
        put("version", 1)
        put("name", name)
        put("base", base)
        put("colors", JSONObject().apply { colors.forEach { (k, v) -> put(k, hex(v)) } })
    }

    fun palette(): Palette {
        var p = Palettes.byId(base)
        fun c(k: String) = colors[k]?.let { Color(it) }
        c("ink")?.let { p = p.copy(ink = it) }
        c("surface")?.let { p = p.copy(surface = it) }
        c("raised")?.let { p = p.copy(raised = it, selected = it.copy(alpha = 1f)) }
        c("paper")?.let { p = p.copy(paper = it) }
        c("muted")?.let { p = p.copy(muted = it) }
        c("eyebrow")?.let { p = p.copy(eyebrow = it) }
        c("hot")?.let { p = p.copy(hot = it) }
        c("violet")?.let { p = p.copy(violet = it) }
        c("love")?.let { p = p.copy(love = it) }
        p = Palettes.withAccent(p, colors["accent"])
        return p.copy(id = "custom", light = p.ink.luminance() > 0.5f)
    }

    companion object {
        fun hex(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

        /** Reads a theme file; refuses unknown formats, bad colours and unreadable text. */
        fun parse(text: String): Result<CustomTheme> = runCatching {
            val o = JSONObject(text.trim())
            require(o.optString("format") == "betteryts-theme") { "That isn't a BetterYTS theme" }
            require(o.optInt("version", 1) == 1) { "This theme is from a newer version" }
            val c = o.optJSONObject("colors") ?: JSONObject()
            val colors = THEME_TOKENS.mapNotNull { (k, _) -> c.optString(k).takeIf { it.isNotBlank() }?.let { k to parseHex(it) } }.toMap()
            val theme = CustomTheme(o.optString("name").take(40).ifBlank { "My theme" }, o.optString("base").takeIf { id -> Palettes.all.any { it.id == id } } ?: "betteryts", colors)
            contrastProblem(theme.palette())?.let { throw IllegalArgumentException(it) }
            theme
        }

        fun parseHex(s: String): Long {
            val h = s.trim().removePrefix("#")
            require(h.length == 6 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Colours look like #FF6A00" }
            return 0xFF000000L or h.toLong(16)
        }
    }
}

/** WCAG contrast ratio between two colours. */
fun contrast(a: Color, b: Color): Float {
    val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (hi + 0.05f) / (lo + 0.05f)
}

/** Why a palette is hard to read, or null if it's fine (text 4.5:1, secondary text and accent 3:1). */
fun contrastProblem(p: Palette): String? = when {
    contrast(p.paper, p.ink) < 4.5f -> "Text needs more contrast with the background"
    contrast(p.paper, p.surface) < 4.5f -> "Text needs more contrast with cards"
    contrast(p.muted, p.ink) < 3f -> "Secondary text is too faint"
    contrast(p.accent, p.ink) < 3f -> "The accent is hard to see on the background"
    else -> null
}

/**
 * Everything about how the app looks and is laid out, beyond the theme. Saved as JSON in the
 * "look" preferences.
 */
data class Look(
    /** flat, glass (translucent surfaces over an ambient backdrop) or ambient (backdrop only). */
    val style: String = "flat",
    val fontScale: Float = 1f,
    val compact: Boolean = false,
    val reducedMotion: Boolean = false,
    val libraryGrid: Boolean = false,
    /** Side rail + roomier layout on wide screens (tablets, foldables, landscape). */
    val wideLayout: Boolean = true,
    val homeOrder: List<String> = HOME_SECTIONS.map { it.first },
    val homeHidden: Set<String> = emptySet(),
    val tabs: List<String> = listOf("Home", "Explore", "Library"),
    val menuHidden: Set<String> = emptySet(),
    val custom: CustomTheme? = null,
    val useCustom: Boolean = false,
) {
    /** Home sections in order; ones added in later versions are appended. */
    val homeSections: List<String> get() = (homeOrder + HOME_SECTIONS.map { it.first }).distinct().filter { it !in homeHidden && HOME_SECTIONS.any { s -> s.first == it } }

    fun toJson(): JSONObject = JSONObject().apply {
        put("style", style)
        put("fontScale", fontScale.toDouble())
        put("compact", compact)
        put("reducedMotion", reducedMotion)
        put("libraryGrid", libraryGrid)
        put("wideLayout", wideLayout)
        put("homeOrder", JSONArray(homeOrder))
        put("homeHidden", JSONArray(homeHidden.toList()))
        put("tabs", JSONArray(tabs))
        put("menuHidden", JSONArray(menuHidden.toList()))
        custom?.let { put("custom", it.toJson()) }
        put("useCustom", useCustom)
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString("look", toJson().toString()).apply()
    }

    companion object {
        private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a?.optString(it)?.takeIf(String::isNotBlank) }

        fun load(prefs: SharedPreferences): Look {
            val o = prefs.getString("look", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Look()
            val tabs = strings(o.optJSONArray("tabs")).filter { it in ALL_TABS }.distinct()
            return Look(
                style = o.optString("style", "flat").takeIf { it in setOf("flat", "glass", "ambient") } ?: "flat",
                fontScale = o.optDouble("fontScale", 1.0).toFloat().coerceIn(0.85f, 1.3f),
                compact = o.optBoolean("compact"),
                reducedMotion = o.optBoolean("reducedMotion"),
                libraryGrid = o.optBoolean("libraryGrid"),
                wideLayout = o.optBoolean("wideLayout", true),
                homeOrder = strings(o.optJSONArray("homeOrder")).ifEmpty { HOME_SECTIONS.map { it.first } },
                homeHidden = strings(o.optJSONArray("homeHidden")).toSet(),
                tabs = if (tabs.size >= 2 && "Home" in tabs) tabs else listOf("Home", "Explore", "Library"),
                menuHidden = strings(o.optJSONArray("menuHidden")).toSet(),
                custom = o.optJSONObject("custom")?.let { CustomTheme.parse(it.toString()).getOrNull() },
                useCustom = o.optBoolean("useCustom"),
            )
        }
    }
}
