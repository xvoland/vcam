package com.darusc.vcamdroid

import android.content.Intent
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.darusc.vcamdroid.databinding.ActivityStreamBinding
import com.darusc.vcamdroid.networking.ConnectionManager
import com.darusc.vcamdroid.networking.PacketType
import com.darusc.vcamdroid.rtsp.Streamer
import com.darusc.vcamdroid.rtsp.StreamOptions
import com.darusc.vcamdroid.util.Logger

class StreamActivity : AppCompatActivity(), SurfaceHolder.Callback, ConnectionManager.ConnectionStateCallback {

    private val TAG = "VCamdroid"

    private lateinit var viewBinding: ActivityStreamBinding

    private val connectionManager = ConnectionManager.getInstance(this)
    private lateinit var streamer: Streamer

    /** True while the desktop client asked us to blank the screen. */
    private var screenOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        viewBinding = ActivityStreamBinding.inflate(layoutInflater)
        connectionManager.setOnBytesReceivedCallback(::onBytesReceived)
        streamer = Streamer(StreamOptions(), this, viewBinding.surfaceView)
        viewBinding.surfaceView.holder.addCallback(this)
        viewBinding.blackoutView.setOnClickListener { wakeFromTouch() }

        setContentView(viewBinding.root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // The surface exists from here on. The streamer starts the preview and
        // applies any stream options that arrived before this callback.
        if (::streamer.isInitialized) {
            streamer.onSurfaceCreated()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        streamer.stop()
    }

    override fun onDisconnected() {
        Toast.makeText(this, "Connection closed by server", Toast.LENGTH_LONG).show()
        Logger.log("STREAM", "TCP server disconnected")
        streamer.stop()
        finish()
    }

    private fun onBytesReceived(buffer: ByteArray, bytes: Int) {
        println(buffer)
        // Packet type is always the first byte
        val type = buffer[0]

        when (type) {
            PacketType.ACTIVATION -> {
                val options = StreamOptions.deserialize(buffer)
                streamer.startStream(options)
            }
            PacketType.CAMERA -> {
                streamer.switchCamera()
            }
            PacketType.RESOLUTION -> {
                val width = (buffer[1].toInt() and 0xFF) or (buffer[2].toInt() shl 8)
                val height = (buffer[3].toInt() and 0xFF) or (buffer[4].toInt() shl 8)
                streamer.setResolution(width, height)
            }
            PacketType.ROTATION -> {
                val degrees = buffer[1].toInt();
                streamer.rotate(degrees)
            }
            PacketType.EFFECT_FILTER -> {
                val filterName = String(buffer, 2, buffer[1].toInt(), Charsets.UTF_8)
                streamer.applyEffectFilter(filterName)
            }
            PacketType.CORRECTION_FILTER -> {
                val filterName = String(buffer, 2, buffer[1].toInt(), Charsets.UTF_8)
                val value = buffer[2 + buffer[1].toInt()].toInt()
                streamer.applyCorrectionFilter(filterName, value)
            }
            PacketType.BITRATE -> {
                val bitrate = (buffer[1].toInt() and 0xFF) or (buffer[2].toInt() shl 8)
                streamer.setBitrate(bitrate)
            }
            PacketType.ADAPTIVE_BITRATE ->  {
                val min = (buffer[1].toInt() and 0xFF) or (buffer[2].toInt() shl 8)
                val max = (buffer[3].toInt() and 0xFF) or (buffer[4].toInt() shl 8)
                streamer.setAdaptiveBitrate(min, max)
            }
            PacketType.STABILIZATION -> {
                streamer.setStabilization(buffer[1].toInt() == 1)
            }
            PacketType.FLASH -> {
                streamer.setFlash(buffer[1].toInt() == 1)
            }
            PacketType.FOCUS -> {
                streamer.setFocus(buffer[1].toInt())
            }
            PacketType.CODEC -> {
                streamer.setH265Codec(buffer[1].toInt() == 1)
            }
            PacketType.FPS -> {
                streamer.setFps(buffer[1].toInt())
            }
            PacketType.ZOOM -> {
                val factor = (buffer[1].toInt() and 0xFF) or
                        ((buffer[2].toInt() and 0xFF) shl 8) or
                        ((buffer[3].toInt() and 0xFF) shl 16) or
                        ((buffer[4].toInt() and 0xFF) shl 24)
                streamer.setZoom(Float.fromBits(factor))
            }
            PacketType.SCREEN -> {
                // Control packets arrive on the TCP reader thread, and the
                // blanking touches views and the window, so hop to the UI thread.
                val off = buffer[1].toInt() == 1
                runOnUiThread { setScreenOff(off) }
            }
            PacketType.FLIP -> {
                val axis = buffer[1].toInt()
                streamer.flip(if (axis == 0) StreamOptions.FlipAxis.VERTICAL else StreamOptions.FlipAxis.HORIZONTAL)
            }
        }
    }

    /**
     * Blanks the screen on request from the desktop client.
     *
     * There is no permission-free way to actually power the display off, so the
     * activity draws a black overlay and drops the window brightness to zero.
     * `FLAG_KEEP_SCREEN_ON` (set in onCreate) keeps the device awake, which
     * means a single tap brings everything back instantly — no lock screen.
     */
    private fun setScreenOff(enabled: Boolean) {
        if (screenOff == enabled) {
            return
        }
        screenOff = enabled
        viewBinding.blackoutView.visibility = if (enabled) View.VISIBLE else View.GONE

        val params = window.attributes
        params.screenBrightness =
            if (enabled) 0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params

        val bars = WindowInsetsControllerCompat(window, viewBinding.root)
        if (enabled) {
            bars.hide(WindowInsetsCompat.Type.systemBars())
            bars.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }

        Logger.log("STREAM", if (enabled) "Screen blanked by the client" else "Screen restored")
    }

    /** A tap on the black overlay turns the screen back on and tells the client. */
    private fun wakeFromTouch() {
        if (!screenOff) {
            return
        }
        setScreenOff(false)
        connectionManager.sendScreenState(false)
    }
}
