# No @JavascriptInterface keep rule: this browser exposes no injected objects to web content.
# Everything a page can reach goes through a web message listener, which is called by name from
# Kotlin and needs no reflective surface kept alive. If that rule ever comes back, so has the
# attack surface it protects.

# Play Services instantiates the cast options provider by the name in the manifest; without
# this the release build loses casting entirely and says nothing about it.
-keep class com.slate.browser.cast.SlateCastOptionsProvider { *; }

# Room generated implementations are resolved reflectively by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Kotlin metadata / coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Compose keeps itself; suppress noisy missing-class warnings from optional deps.
-dontwarn org.jetbrains.annotations.**
