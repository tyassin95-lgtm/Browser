package com.slate.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.app.SearchManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.slate.browser.ui.BrowserScreen
import com.slate.browser.ui.theme.SlateTheme
import com.slate.browser.web.BrowserHost
import com.slate.browser.web.UrlSafety

/**
 * The browser's only activity.
 *
 * It declares a wide `configChanges` set in the manifest, so rotating the phone never destroys
 * it. That is what lets a page keep its scroll position, its JavaScript state, its running
 * video and its form input across an orientation change — and what makes landscape feel like a
 * different layout of the same session rather than a reload.
 */
class MainActivity : ComponentActivity(), BrowserHost {

    private val viewModel: BrowserViewModel by viewModels()

    private var fileChooserCallback: ValueCallback<Array<Uri>?>? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        fileChooserCallback = null
        callback.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val callback = permissionCallback ?: return@registerForActivityResult
        permissionCallback = null
        callback(grants.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // The window always extends through the cutout, in every orientation, and the layout
        // insets itself from what it finds. Switching this per mode used to letterbox landscape
        // on cutout devices and changed the inset values underneath a running layout; keeping it
        // fixed means one inset model has to be right rather than two.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        viewModel.attach(this, this)
        viewModel.bootstrap(webAddressFrom(intent))

        setContent {
            val settings by viewModel.settings.collectAsState()

            // System bars follow the browser's own mode: hidden in fullscreen browsing and
            // while a page element owns the screen, visible otherwise.
            val hideSystemBars = viewModel.isImmersive || viewModel.fullscreenView != null
            LaunchedEffect(hideSystemBars) { applySystemBars(hideSystemBars) }

            // A wide stream is worth turning the phone for: landscape is where a 16:9 video
            // stops being a letterboxed strip and starts using the whole display. The lock is
            // released the moment fullscreen ends, so normal browsing still follows the sensor.
            LaunchedEffect(viewModel.lockLandscapeForMedia) {
                requestedOrientation = if (viewModel.lockLandscapeForMedia) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                }
            }

            // Media should not be interrupted by the screen timing out.
            LaunchedEffect(viewModel.fullscreenView != null || viewModel.isMediaFullscreen) {
                if (viewModel.fullscreenView != null || viewModel.isMediaFullscreen) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            SlateTheme(themeMode = settings.themeMode) {
                BrowserScreen(
                    viewModel = viewModel,
                    appVersion = BuildConfig.VERSION_NAME,
                    onShare = ::share,
                    onOpenExternally = ::openInAnotherApp,
                )
            }
        }

        SlateApplication.trimListeners += ::onSystemTrimMemory
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        webAddressFrom(intent)?.let { viewModel.openInNewTabAndSwitch(it) }
    }

    /**
     * What another app has asked this browser to open, if it is something a browser should
     * open at all.
     *
     * This activity is exported, so any app on the device can start it with any URI it likes —
     * the declared http/https filter constrains implicit intents and nothing else. An address
     * arriving here is therefore untrusted input: only the web is accepted, so a caller cannot
     * steer the browser at `file:` and `content:` URIs it would then be rendering on the
     * device's behalf, or at `javascript:` running in whatever tab is open.
     *
     * Shared text is accepted too, because that is what the SEND filter is for, but it goes
     * through the same door: a link if it is one, a search if it is not.
     */
    private fun webAddressFrom(intent: Intent?): String? {
        if (intent == null) return null
        intent.dataString?.takeIf { it.isNotBlank() }?.let { url ->
            return url.takeIf { UrlSafety.isSafeExternalEntry(it) }
        }
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_WEB_SEARCH) return null
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(SearchManager.QUERY)
        val text = shared?.trim()?.take(MAX_SHARED_TEXT).orEmpty()
        if (text.isEmpty()) return null
        return if (UrlSafety.isSafeExternalEntry(text)) text else viewModel.searchUrlFor(text)
    }

    override fun onPause() {
        super.onPause()
        // The WebView is deliberately NOT paused here. Pausing it would cut off audio and video
        // the moment the user switches apps, which is not how a browser is expected to behave;
        // every mainstream browser keeps playing. Battery is protected instead by hibernating
        // the tabs the user is not watching once the UI is hidden (see SlateApplication), and
        // by the platform's own throttling of background renderers.
        viewModel.persistSessionNow()
    }

    override fun onStop() {
        super.onStop()
        viewModel.captureActiveThumbnail()
    }

    override fun onDestroy() {
        SlateApplication.trimListeners -= ::onSystemTrimMemory
        if (isFinishing) viewModel.onAppExit()
        viewModel.detach()
        super.onDestroy()
    }

    private fun onSystemTrimMemory() = viewModel.onTrimMemory()

    private fun applySystemBars(hidden: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- BrowserHost --------------------------------------------------------

    override fun onEnterElementFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        viewModel.onEnterElementFullscreen(view, callback)
    }

    override fun onExitElementFullscreen() {
        viewModel.onExitElementFullscreen()
    }

    override fun requestSystemPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onResult(true)
            return
        }
        permissionCallback = onResult
        permissionLauncher.launch(missing.toTypedArray())
    }

    override fun openFileChooser(intent: Intent, callback: ValueCallback<Array<Uri>?>): Boolean {
        // A pending chooser must be answered before it is replaced, or the page hangs.
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        return try {
            fileChooserLauncher.launch(intent)
            true
        } catch (_: ActivityNotFoundException) {
            fileChooserCallback = null
            callback.onReceiveValue(null)
            toast("No app can pick a file")
            false
        }
    }

    override fun openExternally(url: String): Boolean {
        // Decided before the URL is parsed, not after: `file:` and `content:` handed to another
        // app leak this browser's own storage, and a web address handed out is how a page moves
        // the user into a different browser.
        if (!UrlSafety.mayLeaveTheBrowser(url)) return false
        val intent = runCatching {
            if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        }.getOrNull() ?: return false

        // A web address is this browser's job. Dispatching one would hand the user to whatever
        // else claims http, which is how a page moves them into another browser.
        val scheme = intent.data?.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return false

        // The parsed intent is attacker-authored in full. A component or selector aims it at
        // one app rather than letting the system resolve it; extras can carry another Intent
        // for an unwitting app to launch on the page's behalf; flags can carry URI grants.
        // Requiring BROWSABLE is what limits the result to activities that expect to be
        // reached from the web at all.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.replaceExtras(null as Bundle?)
        intent.flags = 0
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // And the data must still be something that may leave, after the parse: an
        // `intent:` URL carries its own payload, which the check above never saw.
        val resolved = intent.data?.toString()
        if (resolved != null && !UrlSafety.mayLeaveTheBrowser(resolved)) return false
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun openCastSettings(): Boolean = try {
        startActivity(
            Intent(Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun snack(message: String, actionLabel: String?, action: (() -> Unit)?) {
        viewModel.snack(message)
    }

    // ---- Sharing ------------------------------------------------------------

    private fun share(url: String, title: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(intent, "Share link"))
    }

    /** Hands the current page to another browser or app, without looping back to this one. */
    private fun openInAnotherApp(url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (_: ActivityNotFoundException) {
            toast("No app can open this link")
        }
    }
}

/** A shared payload longer than this is not a link and not a query anyone typed. */
private const val MAX_SHARED_TEXT = 2048
