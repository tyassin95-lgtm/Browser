# Slate

A minimal Android browser. No homepage, no feed, no recommendations — just the page you asked
for, and the smallest amount of chrome needed to get to the next one.

## Installing

`dist/slate-browser-1.1.apk` is a signed release build. Copy it to the phone and open it;
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
the way every mainstream browser behaves. The screen stays awake while anything is fullscreen,
and autoplay is off by default and is a setting.

**Fullscreen video that does not need the site's permission.** A film-strip button appears in the
toolbar whenever a page has a video, and it works whether or not the site's player has a
fullscreen button of its own — including when the stream sits inside a third-party player iframe
that was never marked `allowfullscreen`.

This is the part that is not a wrapper around the Fullscreen API, because that API is exactly
what these sites withhold. An agent injected at document start into *every* frame finds the video
that is actually playing, pins it to the viewport, and undoes whatever was constraining it: the
ancestor `transform`, `contain` or `will-change` that was acting as its containing block, the
`overflow` and `clip-path` that were cropping it, and a page viewport left zoomed or laid out at
desktop width. The stream is fitted with `object-fit: contain`, so it is letterboxed rather than
stretched or cropped; a control toggles to edge-to-edge fill when you would rather trim the
overhang. A black backdrop and the browser's own controls take the place of the site's player
furniture, and a watchdog re-asserts the layout against players that rewrite it.

Where the video lives inside an embedded player, each frame on the way down is expanded in turn,
so the picture ends up filling the display no matter how deeply it was nested. A stream wider
than it is tall holds the phone in landscape for as long as it is playing, which is the
difference between a letterboxed strip and the whole screen. Three independent ways out — swipe
down, the close button, the back gesture — so it is never possible to get stuck.

When a site *does* open its own fullscreen, that is kept: its player usually has a scrubber and a
quality picker the browser has no equivalent for, so touches pass straight through and only the
escape hatches are added on top.

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

```
./gradlew testDebugUnitTest    # 68 tests, Android framework via Robolectric
cd tools && npm install && npm test   # 26 tests, the injected agent against a real DOM
```

The Android tests include `BrowserUiTest` and `MediaFullscreenTest`, which compose the actual
browsing surface with a real ViewModel, real tab manager and real WebViews and drive it the way
a person would. The media agent is JavaScript, so it is tested where it runs: in a DOM, under
jsdom, against pages built to misbehave the way real ones do. Node is not required to build the
app — only to run that suite.

Six real defects were found by writing these. From the browser: a WebView provider that
advertises algorithmic darkening and then throws (crashed on launch), a scrim that swallowed
toolbar taps without dismissing the omnibox, and a desktop/mobile toggle that derived each
user-agent from the previous one. From the media work: video ranking by area, which let a large
paused preview outrank the small stream actually playing; a flat probe deadline, which expired
before a player nested two frames deep could answer; and a rotation rule that read "dimensions
not yet decoded" as landscape and turned the phone on a guess.

## Layout

```
data/    Room entities, DAOs, repository, settings
web/     WebView configuration, WebViewClient, WebChromeClient, downloads, favicons, media agent
assets/  media_agent.js — the in-page half of fullscreen video, injected into every frame
tools/   Node test suite for that agent (not part of the Gradle build)
tabs/    Tab model, the live-WebView budget, session persistence
ui/      Compose surface: browsing screen, chrome, overlays, dialogs, theme
```
