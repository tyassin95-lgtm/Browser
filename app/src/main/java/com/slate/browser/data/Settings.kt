package com.slate.browser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("slate_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SearchEngine(val label: String, private val template: String, val suggestHost: String?) {
    GOOGLE("Google", "https://www.google.com/search?q=%s", "www.google.com"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "duckduckgo.com"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s", null),
    BING("Bing", "https://www.bing.com/search?q=%s", null),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=%s", null),
    ;

    fun urlFor(query: String): String = template.format(java.net.URLEncoder.encode(query, "UTF-8"))
}

/**
 * Every user-visible preference, backed by DataStore so it survives process death and is read
 * asynchronously off the main thread.
 */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val homePage: String = "",
    val javaScriptEnabled: Boolean = true,
    val desktopModeByDefault: Boolean = false,
    /**
     * Blocked by default. A third-party cookie exists to recognise the same person across
     * unrelated sites, which is the mechanism rather than a side effect, and every major
     * browser has either removed it or is removing it. Sign-in flows that genuinely need one
     * survive: this blocks the cookie, not the navigation.
     */
    val blockThirdPartyCookies: Boolean = true,
    val doNotTrack: Boolean = true,
    val allowAutoplay: Boolean = false,
    val blockAds: Boolean = true,
    val blockPopups: Boolean = true,
    val rotateForVideo: Boolean = true,
    val restoreTabs: Boolean = true,
    val saveHistory: Boolean = true,
    val autoImmersiveLandscape: Boolean = false,
    val hideBarsOnScroll: Boolean = true,
    val clearOnExit: Boolean = false,
)

class SettingsStore(private val context: Context) {

    /**
     * The stored preferences, or the defaults if they cannot be read.
     *
     * DataStore reports a corrupt or unreadable file by throwing into the flow. Letting that
     * through would take the whole browser down at launch — the startup path waits on this
     * value before it decides anything — over a preferences file, so an unreadable one is
     * treated as an unset one.
     */
    val settings: Flow<Settings> = context.dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { p ->
        Settings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            searchEngine = p[Keys.ENGINE]?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull() }
                ?: SearchEngine.GOOGLE,
            homePage = p[Keys.HOME_PAGE] ?: "",
            javaScriptEnabled = p[Keys.JAVASCRIPT] ?: true,
            desktopModeByDefault = p[Keys.DESKTOP_DEFAULT] ?: false,
            blockThirdPartyCookies = p[Keys.BLOCK_3P_COOKIES] ?: true,
            doNotTrack = p[Keys.DNT] ?: true,
            allowAutoplay = p[Keys.AUTOPLAY] ?: false,
            blockAds = p[Keys.BLOCK_ADS] ?: true,
            blockPopups = p[Keys.BLOCK_POPUPS] ?: true,
            rotateForVideo = p[Keys.ROTATE_FOR_VIDEO] ?: true,
            restoreTabs = p[Keys.RESTORE_TABS] ?: true,
            saveHistory = p[Keys.SAVE_HISTORY] ?: true,
            autoImmersiveLandscape = p[Keys.AUTO_IMMERSIVE] ?: false,
            hideBarsOnScroll = p[Keys.HIDE_ON_SCROLL] ?: true,
            clearOnExit = p[Keys.CLEAR_ON_EXIT] ?: false,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = put(Keys.THEME, mode.name)
    suspend fun setSearchEngine(engine: SearchEngine) = put(Keys.ENGINE, engine.name)
    suspend fun setHomePage(url: String) = put(Keys.HOME_PAGE, url.trim())
    suspend fun setJavaScript(enabled: Boolean) = put(Keys.JAVASCRIPT, enabled)
    suspend fun setDesktopDefault(enabled: Boolean) = put(Keys.DESKTOP_DEFAULT, enabled)
    suspend fun setBlockThirdPartyCookies(enabled: Boolean) = put(Keys.BLOCK_3P_COOKIES, enabled)
    suspend fun setDoNotTrack(enabled: Boolean) = put(Keys.DNT, enabled)
    suspend fun setAutoplay(enabled: Boolean) = put(Keys.AUTOPLAY, enabled)
    suspend fun setBlockAds(enabled: Boolean) = put(Keys.BLOCK_ADS, enabled)
    suspend fun setBlockPopups(enabled: Boolean) = put(Keys.BLOCK_POPUPS, enabled)
    suspend fun setRotateForVideo(enabled: Boolean) = put(Keys.ROTATE_FOR_VIDEO, enabled)
    suspend fun setRestoreTabs(enabled: Boolean) = put(Keys.RESTORE_TABS, enabled)
    suspend fun setSaveHistory(enabled: Boolean) = put(Keys.SAVE_HISTORY, enabled)
    suspend fun setAutoImmersive(enabled: Boolean) = put(Keys.AUTO_IMMERSIVE, enabled)
    suspend fun setHideBarsOnScroll(enabled: Boolean) = put(Keys.HIDE_ON_SCROLL, enabled)
    suspend fun setClearOnExit(enabled: Boolean) = put(Keys.CLEAR_ON_EXIT, enabled)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val ENGINE = stringPreferencesKey("engine")
        val HOME_PAGE = stringPreferencesKey("home_page")
        val JAVASCRIPT = booleanPreferencesKey("javascript")
        val DESKTOP_DEFAULT = booleanPreferencesKey("desktop_default")
        val BLOCK_3P_COOKIES = booleanPreferencesKey("block_3p_cookies")
        val DNT = booleanPreferencesKey("dnt")
        val AUTOPLAY = booleanPreferencesKey("autoplay")
        val BLOCK_ADS = booleanPreferencesKey("block_ads")
        val BLOCK_POPUPS = booleanPreferencesKey("block_popups")
        val ROTATE_FOR_VIDEO = booleanPreferencesKey("rotate_for_video")
        val RESTORE_TABS = booleanPreferencesKey("restore_tabs")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val AUTO_IMMERSIVE = booleanPreferencesKey("auto_immersive")
        val HIDE_ON_SCROLL = booleanPreferencesKey("hide_on_scroll")
        val CLEAR_ON_EXIT = booleanPreferencesKey("clear_on_exit")
    }
}
