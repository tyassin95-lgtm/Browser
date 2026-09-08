# Vox

A minimal Android browser. No homepage, no feed, no recommendations — just the page you asked
for, and the smallest amount of chrome needed to get to the next one.

## Installing

`dist/vox-browser-2.1.apk` is a signed release build. Copy it to the phone and open it;
Android will ask you to allow installs from your file manager the first time. Minimum Android
8.0 (API 26).

To build it yourself:

```
./gradlew assembleRelease      # dist-ready APK, R8-minified
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

**Tabs.** Creating a tab and selecting one are separate operations in the tab model, not a flag
a caller has to remember: `createTab` never changes what is on screen, and `select` is the only
thing that does. "Open in new tab" therefore means what it says, and a window a page opens for
itself lands behind the page you are reading rather than in front of it.

**Ad and pop-up blocking.** The rules are the published community lists — EasyList,
EasyPrivacy and AdGuard's mobile list, about 115,000 network rules and 30,000 element-hiding
rules — carried in the Adblock Plus syntax they are published in and matched by the browser's
own engine. Keeping the published format is the point: updating the blocking means dropping in
newer copies of the lists (`node tools/build-filters.mjs --fetch`), not editing a hand-written
set of domains that falls behind the week it is written.

A hundred thousand rules cannot be scanned per request, so they are indexed the way every
serious blocker indexes them: each rule contributes one literal token from its pattern, and a
request only tests the rules filed under the tokens its own address contains. A typical lookup
compares a handful of rules and allocates nothing but those token substrings, which is what
makes it safe on the network threads every subresource passes through. `$third-party`,
`$domain=`, `$script`/`$image`/`$subdocument` and `@@` exceptions all mean what they mean in
the lists, so a rule scoped to a site applies there and nowhere else. Compiling the lists takes
about a third of a second and 26MB, done once at launch on a background thread.

Element hiding is the second pass, for the advertising a network rule cannot reach because the
site serves it from its own origin: the list rules scoped to the site being visited, plus the
unscoped ones that name advertising unambiguously in the identifier itself. "ad" alone does not
qualify — it matches *header*, *gradient* and *download*, and a blocker that hides those is
worse than one that misses an advert.

Requests are only half of it. A transparent layer over a play button, six windows opened from
one tap, a notification prompt on arrival and fullscreen taken for an advert are all the site's
own first-party script and never touch the network, so there is an in-page guard for them too,
installed at document start in every frame. It ties each of those to a real user gesture rather
than to any particular site: a window is granted for a click and not for the `pointerdown`
before one, which is exactly the line between "open in a new window" and a pop-under that
spends your tap before it reaches the player. A full-viewport, contentless, transparent layer
over the thing you tapped is identified from what it is, disabled, and the tap passed to
whatever it was covering — so the first tap plays the video.

Windows and navigations then go through a single browser-side policy, in layers, because each
catches a different abuse. WebView reports whether a navigation carried a gesture but not
*which* gesture, so one tap can be replayed into a dozen `window.open` calls that all claim to
be user initiated. Identity is reconstructed from the touch stream instead: a real touch starts
an activation and the first thing to ask for it consumes it, so one tap opens at most one
window and launches at most one app. A window the page opened without one is refused, and the
browser finds out where it was headed and offers it by name so a payment or sign-in window
stays one tap away.

Leaving the browser always asks first, and an `intent:` URL carrying an ordinary web address is
kept here rather than dispatched — handing a web address to another app is precisely how a page
moves you into a different browser. A page moving you off-site with no touch behind it is
stopped with an Allow, while server redirects, same-site navigation and anything you actually
tapped pass untouched.

## Security and privacy

Everything a page supplies is attacker-controlled: its URL, its markup, its scripts, its
headers, the name of the file it offers, the address it asks to be handed to another app. The
browser is built to that assumption rather than to the assumption that most sites are honest.

**Nothing native is exposed to web content.** There is no `addJavascriptInterface` anywhere in
the codebase, and no ProGuard rule keeping one alive. An injected object appears in *every*
frame of a WebView — an advert nested three frames deep inside an unrelated site can call the
same native methods as the page the user is on, and the receiving code cannot tell them apart.
Both bridges are `WebViewCompat.addWebMessageListener` instead, which reports the sender's
origin and whether it is the main frame; anything else is dropped unread, as are messages that
are not bounded, well-formed JSON. Pulling a `blob:` download back through the page — the one
path where content supplies bytes the browser then writes — additionally carries a single-use
token generated for that one download, must come from the origin that asked for it, and is
capped in size.

**Only the web is an address.** `javascript:` typed or pasted into the address bar runs against
the page already open, which is how someone is talked into attacking their own signed-in
session; `data:` renders attacker-authored markup under an address bar with nothing useful to
show. Those, and `file:`, `content:`, `blob:` and the rest, are never navigated to, never
accepted from another app's intent, and never offered as long-press actions. Schemes are read
the way an engine reads them, with tabs and newlines stripped first, so `java\nscript:` is
recognised as what it will become rather than as what it looks like.

**The address bar cannot lie.** The host shown is the one that will actually be reached, so
`https://accounts.google.com@evil.example/` reads as `evil.example`. Unicode hosts are shown as
Unicode only when they cannot impersonate: Unicode's Highly Restrictive profile, so a single
script or a real language's combination reads as itself and a Latin word with one Cyrillic
letter substituted into it is shown encoded. A padlock is a claim about the connection, so a
page reached by accepting a certificate warning shows a warning instead of a lock, and that
state is cleared by the next navigation.

**Transport security is not traded away.** Mixed content is refused outright rather than
allowed for images, because passive mixed content is exactly what an attacker on the path
replaces while the lock stays up. Certificate errors on subresources are refused with nothing
offered — a user cannot meaningfully consent to a frame they never asked for, and a prompt
naming a host they did not navigate to is a phishing surface of its own. Only the document the
user actually asked for is worth a decision. User-installed certificate authorities are not
trusted. HTTP authentication is refused rather than answered: there is no credential store
behind it, and a password prompt on behalf of a host the user was merely redirected to is
phishing with the browser's own chrome around it.

**Capabilities are asked for, not assumed.** Camera, microphone, location, MIDI and protected
media are refused outright on origins the platform does not consider trustworthy, and otherwise
granted in stages — the origin must be allowed by the user, and the app must hold the Android
runtime permission. Only the resources the user was actually shown are granted, so a request
naming something the browser has no words for is refused rather than approved alongside the
rest. Protected media is included because it is a durable per-device identifier as much as it
is what makes commercial video play. Modal dialogs are capped per document and their text is
trimmed, so a page cannot hold the browser with a loop of `alert()` or push the buttons off the
screen.

**Downloads are named by what they are.** A file that runs code when it is opened — an
installable package, a script — is worth a sentence and a decision, and it is named by its real
extension rather than by what the page called it, so `Invoice-2024.pdf.apk` is described as
what it will do. Ordinary files are not interrupted: a browser that asks about every photo
teaches people to say yes without reading.

**Browsing data stays on the device.** The database is not in the backup set, cloud or
device-transfer: a browsing history is a record of everywhere someone has been, and a backup is
a copy of it the browser no longer controls. Preferences are all that leave. Third-party
cookies are blocked by default, `X-Requested-With`-style identification aside (see
`WebViewConfigurator` for why that one is not yet done), and Do Not Track and Global Privacy
Control are sent because a browser that omits them is speaking for the user by silence.

**Failing safely.** Web content cannot reach `file:` or `content:` URLs, cannot open a window
without a gesture at three independent layers, cannot escape the downloads folder through a
`Content-Disposition` header, and cannot make the browser do unbounded work through a bridge
message or a blob. A dead renderer rebuilds the tab rather than taking the app down. An intent
handed out to another app has its component, selector, extras and flags stripped and must
declare `BROWSABLE`, so a page cannot aim the device at a chosen app or smuggle a second intent
through an unwitting one. The activity keeps its own task affinity so another app cannot have
the browser's window appear inside its task.

**Long press.** Links and images get a sheet of what applies to them and nothing else — open in
a new or background tab, copy, share, save the image. Text, form fields and anything selectable
are left to the page, so selection handles and the platform text menu behave normally.

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

**The keyboard.** The window does not resize for it. This browser draws edge to edge, which
means the IME arrives as an inset like any other and nothing moves unless the layout moves it,
so the keyboard is part of the same expression as the system bars and the cutout rather than a
case handled somewhere else. The bottom inset is the *larger* of the navigation bar and the
keyboard, never the two added together, and never a number: on a device that reports a 320dp
keyboard the toolbar rises 320dp, and on one that reports 280 it rises 280.

Left out of that expression — which is how it was — the toolbar stayed at the bottom of the
window with the keyboard drawn on top of it, so the address bar could be typed into and not
seen, and the page kept its full height with the bottom of it covered. One omission, both
symptoms, in portrait and landscape alike.

What the layout follows is the *target* of the IME animation rather than its current position.
The page is a WebView, and following the animation would relayout and re-raster it on every
frame of the keyboard sliding in — the same cost that made scrolling tear — for an effect
nobody can see behind a moving keyboard. Settling once, at the size the keyboard is going to
be, also means the page is asked to find room for the focused field exactly once. For the same
reason the toolbar stops collapsing on scroll while the keyboard is up: revealing a focused
field makes the page scroll, and that scroll is the keyboard's doing, not a request for more
room.

Giving the page the smaller viewport is only half of it, because only the page knows where
inside itself the field being typed into actually is — in a scroller, in a fixed bar, in a
cross-origin frame. So the other half is a small script at document start in every frame that
watches the viewport and brings the focused element back into view when it changes. It asks for
the nearest position rather than a particular one, so an element already visible is left alone
and nothing fights the engine if the engine gets there first.

Chrome visibility belongs to the tab, never to the browser, so a tab that scrolled its toolbar
away cannot hand that state to one you have just opened or switched to — which on a page with
nothing to scroll would leave no way to get it back. Navigating, switching, creating a tab,
focusing the omnibox and leaving fullscreen all restore it; scrolling a page that scrolls is the
only thing that can take it away.

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

**Casting.** Whatever is playing can be sent to a nearby receiver from the same media controls
that drive it on the phone. Google Cast is the mechanism — Chromecast, Android TV, Google TV
and the televisions and speakers with it built in — because it is the only casting stack on
Android with first-party discovery, a maintained library and a receiver on enough hardware to
be worth the name. It is also the one Chrome uses on this platform. DLNA would mean an
unmaintained third-party stack and hand-rolled SSDP; AirPlay is not open to Android apps;
screen mirroring sends the whole phone rather than the media, and the system already offers it.

The transport does not change. While a receiver has the media, its position, duration and
playback state are merged over the page's report and the same buttons drive the receiver
instead of the video element — one set of controls, two backends, rather than a second player
that behaves differently. Leaving fullscreen, rotating the phone and switching tabs all leave
the session alone; the toolbar's media button becomes the sign that casting is happening and
the way back to the controls.

What makes this more than a button is that a receiver fetches the stream itself, over its own
connection, with none of the browser's cookies, headers or origin. The phone playing something
is therefore no evidence that a television could, and the interesting work is in saying so.
A stream assembled in the page by Media Source has a `blob:` address that means nothing
anywhere else; protected content is decrypted by the phone as it plays and a licence belongs to
the site rather than to the browser; an address only this device can resolve is not an address.
Each of those is refused with the actual reason rather than sent and left to fail on a black
screen. What survives that is then *asked the question the receiver will ask*: one anonymous
ranged request from this device, no cookies, short timeout. A stream that plays here because
the user is signed in answers a stranger with a 403, or — more often, and more quietly — with a
sign-in page carrying a perfectly successful status code. Both become a sentence before a
session is started rather than a television showing nothing.

Nothing is downloaded and re-uploaded: the receiver is given the address and fetches the
original, so the quality is whatever the source serves. Discovery runs quietly while there is
something castable on the page and scans hard only while the picker is open, because a button
that promises to go looking is worse than no button and an active scan is not free. Ending
a session brings playback back to the phone at the position the receiver reached.

**Media controls.** Play and pause, a scrub bar with position and duration, and volume. The
elapsed time follows the thumb while a drag is in progress and the stream the rest of the time,
so the number and the bar never disagree mid-gesture.

A live stream is treated as live: no scrub bar where there is nothing to scrub, a DVR bar where
the stream keeps a rewind buffer, and a badge that turns into
one-tap "go live" once you are behind the edge. Controls fade out on their own and come back on
a tap.

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
./gradlew testDebugUnitTest              # Android framework via Robolectric
cd tools && npm install && npm test      # the injected agent against a real DOM
cd tools && node test-media-e2e.js       # fullscreen playback in real Chromium, on real video
cd tools && node test-popup-guard.mjs    # the in-page guard against real pop-up techniques
cd tools && node test-error-recovery.mjs # the content probe against real challenge pages
cd tools && node test-keyboard-viewport.mjs # focused fields staying visible in a real engine
cd tools && node test-bridge-isolation.mjs # what a hostile iframe can reach, in a real engine
cd tools && node test-cast-source.js     # what the browser learns about a page's media
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

Three suites answer the question a unit test cannot: *did the user's screen change?* The media
suite drives the shipped agent inside real Chromium, over real HTTP with range requests, against
a thirty-second clip whose every second is painted a different flat colour — so reading one
pixel out of the decoded frame says where playback actually is. The seek bar, play, pause,
volume, Media Source streams and a player in a cross-origin iframe are all checked that way.
The pop-up suite runs the shipped in-page guard against a page that uses the real techniques —
a transparent catcher over the play button, six windows from one tap, a notification prompt on
arrival, an exit trap — and asserts on what the user gets: the play button receives the tap, no
pop-under opens, and a genuine click-driven window still opens once. Each check is run with the
guard off first, so a check that would pass either way is caught. The error suite runs the
browser's own "did the server send anything" probe — the exact expression, lifted out of the
Kotlin source — against anti-bot challenge bodies captured from live sites.

Each of them found a defect the unit tests had passed over. The fullscreen overlay's seek
callbacks were never passed from the screen to the overlay at all: they carried no-op defaults,
so every drag was silently swallowed while the overlay animated as though it had worked, and
the tests all called the ViewModel directly and never noticed. The pop-up guard's first version
intercepted `pointerdown`, which stopped the page's own handlers and therefore stopped the video
from playing at all. And the content probe's first version counted only visible text, which
condemned exactly the page it exists to protect: a real Cloudflare challenge parses to four
empty elements and fills them in from script a moment later.

The blocking is checked against traffic the reported sites really produce: the third-party
addresses embedded in the live markup of jav.guru, sxyprn.com, pimpbunny.com and
xmoviesforyou.com, replayed through the shipped engine and the shipped lists. The assertion is
on the verdicts rather than on a score — the advertising and tracking hosts those pages embed
are refused, and the hosts carrying their images and video are not. A blocker judged only on
how much it blocks would rate well and leave you with a broken site.

Two environment limits worth naming. A Material3 text field inside a dialog never reports idle
under Robolectric, so those few dialogs are covered at the ViewModel level instead of by driving
their UI. And desktop Chromium substitutes its own error document for a response with no body at
all, where Android WebView leaves the page blank — so that one case in the error suite reports
itself as unobservable rather than claiming a result it did not produce.

## Layout

```
data/    Room entities, DAOs, repository, settings
web/     WebView configuration, clients, blocking, downloads, desktop mode, gestures, media
web/filter/  the Adblock Plus rule parser, network matcher and element-hiding index
assets/  media_agent.js, popup_guard.js and focus_visibility.js, injected into every frame;
         filters/ — the blocking lists
tools/   Node test suites and the filter-list build script (not part of the Gradle build)
tabs/    Tab model, the live-WebView budget, session persistence
ui/      Compose surface: browsing screen, chrome, overlays, dialogs, theme
```
