package com.slate.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

        viewModel.attach(this, this)
        viewModel.bootstrap(intent?.dataString?.takeIf { it.isNotBlank() })

        setContent {
            val settings by viewModel.settings.collectAsState()

            // System bars follow the browser's own mode: hidden in fullscreen browsing and
            // while a page element owns the screen, visible otherwise.
            val hideSystemBars = viewModel.isImmersive || viewModel.fullscreenView != null
            LaunchedEffect(hideSystemBars) { applySystemBars(hideSystemBars) }

            // Media should not be interrupted by the screen timing out.
            LaunchedEffect(viewModel.fullscreenView != null) {
                if (viewModel.fullscreenView != null) {
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
        intent.dataString?.takeIf { it.isNotBlank() }?.let { viewModel.openInNewTab(it) }
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
        // Cutouts are usable in fullscreen; in normal browsing the page stays clear of them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (hidden) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
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
        val intent = runCatching {
            if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        }.getOrNull() ?: return false

        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
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
