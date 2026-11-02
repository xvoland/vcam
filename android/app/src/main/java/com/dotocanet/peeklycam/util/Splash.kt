// (c) 2026, Vitalii Tereshchuk / xVoLAnD (https://dotoca.net) All rights reserved.
// Modifications to the original darusc/VCamdroid project (MIT License).

package com.dotocanet.peeklycam.util

import android.view.View
import android.widget.TextView
import com.dotocanet.peeklycam.R

/**
 * Startup splash overlay (activity_main.xml and activity_stream.xml share the
 * same block: id splashOverlay with icon/name/version). Shown for a moment,
 * then fades out smoothly.
 *
 * It lives on BOTH activities because the app can switch to StreamActivity
 * within a second of launch (USB connection), which would otherwise swallow
 * the main screen's splash entirely.
 */
object Splash {
    fun run(overlay: View, versionLabel: TextView, versionName: String) {
        versionLabel.text = versionLabel.context.getString(R.string.splash_version, versionName)
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlay.postDelayed({
            overlay.animate()
                .alpha(0f)
                .setDuration(800)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }
                .start()
        }, 1400)
    }
}
