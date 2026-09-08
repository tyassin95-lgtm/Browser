package com.slate.browser.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * How this browser presents itself to a receiver.
 *
 * The Default Media Receiver is used deliberately. A custom receiver would mean a registered
 * application ID tied to one developer account, and would buy nothing a browser needs: the
 * default receiver already plays MP4, WebM, HLS and DASH, which is the whole set of things a
 * page can hand over as a plain address. What a custom receiver *would* add — a licence proxy
 * for protected content — cannot be built from here anyway, because the licences belong to the
 * site, not to the browser. That limit is reported to the user rather than papered over.
 *
 * Instantiated reflectively by Play Services from the manifest, which is why it is kept in the
 * release build by name; see proguard-rules.pro.
 */
class SlateCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            // Ending the session stops the receiver rather than leaving a television sitting on
            // a paused frame of something the user has walked away from.
            .setStopReceiverApplicationWhenEndingSession(true)
            // The framework's own reconnection is kept: it is the platform's answer to a Wi-Fi
            // drop or a moment out of range, and rebuilding that by hand would be exactly the
            // custom machinery worth avoiding. It brings an unexported service and the
            // FOREGROUND_SERVICE permission with it, which is the price of that capability.
            .setEnableReconnectionService(true)
            .setCastMediaOptions(
                // No notification and no media session. A permanent notification is a large
                // piece of UI for a browser that is meant to disappear, and the controls it
                // would carry are the ones already in the media overlay. The receiver's own
                // remote still works, and so does the phone's volume rocker.
                CastMediaOptions.Builder()
                    .setMediaSessionEnabled(false)
                    .setNotificationOptions(null)
                    .build(),
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
