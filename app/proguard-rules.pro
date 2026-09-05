# Keep the JS bridge surface reachable from web content.
-keepclassmembers class com.slate.browser.web.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Room generated implementations are resolved reflectively by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Kotlin metadata / coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Compose keeps itself; suppress noisy missing-class warnings from optional deps.
-dontwarn org.jetbrains.annotations.**
