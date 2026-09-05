# Slate

A minimal Android browser. No homepage, no feed, no recommendations — just the page you asked
for, and the smallest amount of chrome needed to get to the next one.

## Installing

`dist/slate-browser-1.0.apk` is a signed release build. Copy it to the phone and open it;
Android will ask you to allow installs from your file manager the first time. Minimum Android
8.0 (API 26).

To build it yourself:

```
./gradlew assembleRelease      # dist-ready APK, R8-minified, ~1.5 MB
./gradlew assembleDebug        # unminified, debuggable
./gradlew testDebugUnitTest    # the full test suite
./gradlew lintDebug            # static analysis
```

The release build is signed with the checked-in `slate-release.jks` so that a clone of this
repo produces an installable APK. **It is a side-loading key, not a distribution key** — replace
it and `keystore.properties` before publishing anywhere.

## What it does

**Browsing.** Tabs with a visual switcher, back/forward, find in page, per-tab desktop/mobile
switching, downloads, file uploads, pop-ups, camera/microphone/location prompts, page dialogs,
and certificate warnings that name the host and make proceeding an explicit choice.

**History and favourites.** Both are searchable. History is grouped by day and collapsed to one
row per page with a visit count; it can be cleared by the hour, by the day, or entirely.
Favourites can be renamed and filed into folders.

**Orientation.** Portrait and landscape are different layouts, not the same bar rotated. Portrait
puts a single omnibox pill at the bottom where thumbs are. Landscape moves a shorter bar to the
top — vertical space is the scarce resource sideways — and uses the extra width for real back,
forward and fullscreen controls. Rotating never reloads the page: the Activity handles the
configuration change itself, so scroll position, form input, JavaScript state and playing video
all survive.

**Fullscreen browsing.** A landscape mode that hides the browser's chrome and the system bars
so the page owns the entire screen, cutout included. Pull down from the top edge to leave; a
hint says so on the way in. It can be set to engage automatically whenever the phone is turned
sideways.

**Media.** Video and audio play in the background instead of being cut off when you switch apps,
the way every mainstream browser behaves. Any page element can take the whole screen through the
Fullscreen API, and the menu can put the largest video on the page into fullscreen directly.
The screen stays awake while an element is fullscreen. Autoplay is off by default and is a
setting.

## How it is put together

Single Activity, Jetpack Compose, one WebView per live tab.

**Tabs and memory** (`tabs/TabManager.kt`) — a WebView costs several megabytes of native memory
and keeps a renderer process alive, so a browser holding one per tab falls over at a dozen tabs
on an ordinary phone. At most four are resident. Beyond that the least recently used tab is
*hibernated*: its back/forward list is serialised and the view destroyed. Waking it restores the
same history and scroll position. Every inactive tab hibernates once the UI is hidden.

**Chrome overlays the page** rather than displacing it, so showing and hiding the toolbar never
reflows the WebView — the most expensive thing a browser UI can do mid-scroll. The bar's travel
distance is measured, not assumed, so it clears whatever the system insets add.

**Persistence** — favourites, history and folders live in Room; preferences in DataStore; the
open-tab session in a small JSON file that is rewritten on navigation and is worthless if stale.
Restoring a session builds no WebViews: tabs come back as titles and URLs and only touch the
network when opened.

**Privacy** — favicons are only ever learned from pages actually visited and cached locally;
nothing is fetched from a favicon service. Omnibox suggestions come from local history and
favourites alone, so no keystroke leaves the device. Do Not Track and Sec-GPC are sent by
default. The network config trusts system certificate authorities only.

**The JavaScript bridge** is a single object used to pull `blob:` downloads back out of a page,
and it only accepts a call while the browser is genuinely waiting for one the user asked for.

## Tests

53 tests run on the JVM against the real Android framework via Robolectric — including
`BrowserUiTest`, which composes the actual browsing surface with a real ViewModel, real tab
manager and real WebViews, and drives it the way a person would.

Three real defects were found by writing them: a WebView provider that advertises algorithmic
darkening and then throws (crashed the browser on launch), a scrim that swallowed toolbar taps
without dismissing the omnibox editor, and a desktop/mobile toggle that derived each user-agent
from the previous one so switching back left the desktop string in place.

## Layout

```
data/    Room entities, DAOs, repository, settings
web/     WebView configuration, WebViewClient, WebChromeClient, downloads, favicons
tabs/    Tab model, the live-WebView budget, session persistence
ui/      Compose surface: browsing screen, chrome, overlays, dialogs, theme
```
