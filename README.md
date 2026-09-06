# Slate

A minimal Android browser. No homepage, no feed, no recommendations — just the page you asked
for, and the smallest amount of chrome needed to get to the next one.

## Installing

`dist/slate-browser-1.4.apk` is a signed release build. Copy it to the phone and open it;
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

**Browsing.** Tabs with a visual switcher, find in page, downloads, file uploads, pop-ups,
camera/microphone/location prompts, page dialogs, and certificate warnings that name the host
and make proceeding an explicit choice.

**Navigation by swipe.** Drag right to go back, left to go forward, from anywhere on the page.
The page is offered every touch first and the gesture is only taken over once the drag is
clearly horizontal *and* nothing on the page wanted it — the document cannot pan any further,
and the in-page agent found no horizontal scroller, slider, canvas or live text selection under
the finger. Carousels, maps, range inputs and selection handles keep working. An arrow fills in
as the swipe approaches the commit distance, so it can be judged and abandoned mid-gesture.
Back also unwinds the browser's own layers in the order they appear, page history included.

**Desktop sites.** Per tab, and remembered for that tab across restarts. Both halves are done:
the user-agent *and* the layout viewport, because a responsive site reads its own viewport meta
and would otherwise keep serving the phone layout however the UA is dressed up.

**History and favourites.** Both are searchable. History is grouped by day and collapsed to one
row per page with a visit count; it can be cleared by the hour, by the day, or entirely.
Favourites can be renamed and filed into folders.

**Orientation.** Portrait and landscape are different layouts, not the same bar rotated. Portrait
puts a single omnibox pill at the bottom where thumbs are. Landscape moves a shorter bar to the
top — vertical space is the scarce resource sideways — and uses the extra width for real back,
forward and fullscreen controls. Rotating never reloads the page: the Activity handles the
configuration change itself, so scroll position, form input, JavaScript state and playing video
all survive.

**Window layout.** The page and the chrome are siblings in a column, never stacked: a toolbar
drawn over the page is how content becomes unreachable on a site that cannot scroll. Showing or
hiding the toolbar is therefore a real resize, and it snaps rather than slides — animating the
height would relayout the page on every frame, and a WebView reflow is far too expensive to do
sixty times a second. System insets are split between the two so that together they cover every
edge and neither sits under one, recomputed from whatever the device reports rather than from
assumptions about where the bars are. The case that motivates it is a navigation bar that moves
to the side in landscape, which otherwise puts the menu button underneath it.

Because moving the toolbar resizes the page, the decision to move it is deliberately reluctant
and is never acted on while the page is moving. Travel is accumulated in one direction and
discarded on reversal, distances are in dp so a 4x panel is not four times more sensitive, a
frame too large to have come from a finger is ignored — that is the signature of the browser's
own resize, and acting on it closes a loop of resize, scroll, resize — and the change itself
waits for scrolling to stop. One resize on a still page is invisible; the same resize under a
live compositor is what tears, and doing it every frame tears continuously. The page and the
WebView are both painted opaque so the compositor is handed a finished layer rather than
blending one, and no gap can expose the previous frame.

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
ancestor `transform`, `contain`, `isolation` or `will-change` that was acting as its containing
block or its stacking context, the `overflow` and `clip-path` that were cropping it, and a page
viewport left zoomed or laid out at desktop width. The stream is fitted with
`object-fit: contain`, so it is letterboxed rather than stretched or cropped; a control toggles
to edge-to-edge fill when you would rather trim the overhang.

Everything not on the path to the video is hidden outright rather than covered by a black
overlay. An overlay is the wrong tool twice over: a hardware-decoded or WebRTC video is
composited on its own surface and an opaque div can land in front of it — which shows as a black
screen with the audio still playing — and an overlay only wins on z-index within one stacking
context, so any ancestor that quietly creates one puts the page's furniture back on top. Picking
the video is equally deliberate: the element making sound outranks a larger silent one, hidden
decoys are skipped, and a canvas the player draws into is promoted alongside the media element
so canvas-rendered players show a picture too. A watchdog re-asserts all of it against players
that rewrite their own layout.

**Media controls.** Play and pause, a scrub bar with position and duration, and volume. A live
stream is treated as live: no scrub bar where there is nothing to scrub, a DVR bar where the
stream keeps a rewind buffer, and a badge that turns into one-tap "go live" once you are behind
the edge. Controls fade out on their own and come back on a tap.

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
./gradlew testDebugUnitTest    # 118 tests, Android framework via Robolectric
cd tools && npm install && npm test   # 31 tests, the injected agent against a real DOM
```

The Android tests include `BrowserUiTest` and `MediaFullscreenTest`, which compose the actual
browsing surface with a real ViewModel, real tab manager and real WebViews and drive it the way
a person would. The media agent is JavaScript, so it is tested where it runs: in a DOM, under
jsdom, against pages built to misbehave the way real ones do. Node is not required to build the
app — only to run that suite.

Nine real defects were found by writing these. From the browser: a WebView provider that
advertises algorithmic darkening and then throws (crashed on launch); a scrim that swallowed
toolbar taps without dismissing the omnibox; a desktop/mobile toggle that derived each
user-agent from the previous one; a configurator that replaced an attached WebView's
`FrameLayout.LayoutParams` with bare `ViewGroup.LayoutParams`, so every preference toggle made
the next layout pass throw; no back handler for page history at all, so the system back gesture
closed the browser instead of navigating; and a bookmark star that could settle on the wrong
state after two quick taps. From the media work: video ranking by area, which let a large paused
preview outrank the small stream actually playing; a flat probe deadline, which expired before a
player nested two frames deep could answer; and a rotation rule that read "dimensions not yet
decoded" as landscape and turned the phone on a guess.

`ScrollRenderingTest` feeds real scroll streams — flings, repeated swipes, jitter — and counts
how many times the layout would be resized; the answer has to be zero during the scroll and one
after it. Those tests were checked against the previous implementation first, where four of them
fail. `LayoutInsetsTest` dispatches real window insets into the composition and asserts on measured
bounds, so "the page never sits under the toolbar" and "the menu button never sits under a side
navigation bar" are checked as geometry rather than assumed.

One environment limit worth naming: a Material3 text field inside a dialog never reports idle
under Robolectric, so those few dialogs are covered at the ViewModel level instead of by driving
their UI.

## Layout

```
data/    Room entities, DAOs, repository, settings
web/     WebView configuration, clients, downloads, favicons, desktop mode, gestures, media
assets/  media_agent.js — the in-page half of fullscreen video, injected into every frame
tools/   Node test suite for that agent (not part of the Gradle build)
tabs/    Tab model, the live-WebView budget, session persistence
ui/      Compose surface: browsing screen, chrome, overlays, dialogs, theme
```
