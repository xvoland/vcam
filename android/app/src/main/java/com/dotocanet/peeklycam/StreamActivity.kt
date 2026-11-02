// (c) 2026, Vitalii Tereshchuk / xVoLAnD (https://dotoca.net) All rights reserved.
// Modifications to the original darusc/VCamdroid project (MIT License).

package com.dotocanet.peeklycam

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dotocanet.peeklycam.databinding.ActivityStreamBinding
import com.dotocanet.peeklycam.networking.ConnectionManager
import com.dotocanet.peeklycam.networking.PacketType
import com.dotocanet.peeklycam.rtsp.Streamer
import com.dotocanet.peeklycam.rtsp.StreamOptions
import com.dotocanet.peeklycam.util.Logger

class StreamActivity : AppCompatActivity(), SurfaceHolder.Callback, ConnectionManager.ConnectionStateCallback {

    private val TAG = "PeeklyCam"

    private lateinit var viewBinding: ActivityStreamBinding

    private val connectionManager = ConnectionManager.getInstance(this)
    private lateinit var streamer: Streamer

    /**
     * Control packets can arrive in fragments (TCP is a byte stream; the
     * CAMERA packet is a single byte, which used to be dropped entirely). Bytes
     * are buffered here and cut into complete packets before dispatch.
     */
    private var rxLeftover = ByteArray(0)

    /** True while the desktop client asked us to blank the screen. */
    private var screenOff = false

    /**
     * The last request from the desktop client. Kept separately from
     * [screenOff] because a request can arrive before the SurfaceView has a
     * surface: showing an opaque overlay at that point stops the surface from
     * ever being created, and the stream would never start.
     */
    private var screenOffRequested = false

    /** When the screen was blanked, to ignore the digitizer's settling noise. */
    private var blankedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        viewBinding = ActivityStreamBinding.inflate(layoutInflater)
        // Create the streamer *before* registering the callback. The socket is
        // already open when this activity starts, so the reader thread can
        // deliver a packet immediately; the callback used to hit
        // "lateinit property streamer has not been initialized", which closed
        // the connection and dropped the freshly started stream.
        streamer = Streamer(StreamOptions(), this, viewBinding.surfaceView)
        connectionManager.setOnBytesReceivedCallback(::onBytesReceived)
        viewBinding.surfaceView.holder.addCallback(this)
        // ACTION_DOWN rather than a click: the overlay must react on the very
        // first touch, even if the finger moves a little.
        viewBinding.blackoutView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                Logger.log("STREAM", "Touch on the black overlay at " +
                        "${event.x.toInt()},${event.y.toInt()}")
                wakeFromTouch()
            }
            true
        }

        setContentView(viewBinding.root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // The surface exists from here on. The streamer starts the preview and
        // applies any stream options that arrived before this callback.
        if (::streamer.isInitialized) {
            streamer.onSurfaceCreated()
        }
        // A blanking request that arrived before the surface existed is applied
        // now that it does.
        if (screenOffRequested && !screenOff) {
            applyScreenOff(true)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        streamer.stop()
    }

    override fun onDisconnected() {
        Toast.makeText(this, "Connection closed by server", Toast.LENGTH_LONG).show()
        Logger.log("STREAM", "TCP server disconnected")
        if (::streamer.isInitialized) {
            streamer.stop()
        }
        finish()
    }

    override fun onDestroy() {
        // The connection manager is a singleton and outlives this activity:
        // drop the callback so no packet is delivered to destroyed views.
        connectionManager.setOnBytesReceivedCallback(null)
        if (isFinishing) {
            // Leaving the stream on purpose (Back): close the session instead
            // of leaving it open behind the pairing screen.
            connectionManager.disconnect()
        }
        super.onDestroy()
    }

    private fun onBytesReceived(buffer: ByteArray, bytes: Int) {
        // Never touch a streamer that does not exist yet.
        if (bytes <= 0 || !::streamer.isInitialized) return
        // Accumulate and split into complete packets; a TCP read can end
        // mid-packet or contain several of them.
        val merged = ByteArray(rxLeftover.size + bytes)
        rxLeftover.copyInto(merged)
        buffer.copyInto(merged, rxLeftover.size, 0, bytes)
        var offset = 0
        val end = merged.size
        while (offset < end) {
            val type = merged[offset]
            val length = packetLength(type, merged, offset, end)
            if (length < 0) {
                // Incomplete packet: keep the rest and wait for more bytes.
                break
            }
            val packet = merged.copyOfRange(offset, offset + length)
            offset += length
            handlePacket(type, packet)
        }
        rxLeftover = if (offset >= end) ByteArray(0) else merged.copyOfRange(offset, end)
    }

    /**
     * Bytes of a full packet starting at [offset], or -1 while more data is
     * needed. Payload lengths follow docs/PROTOCOL.md in the Rust repo.
     * ACTIVATION and the junk-tolerant filter packets carry their own lengths.
     */
    private fun packetLength(type: Byte, buffer: ByteArray, offset: Int, end: Int): Int {
        return when (type) {
            PacketType.ACTIVATION -> {
                // type(1) + fixed fields(36) = 37; then the slider count.
                // Minimum full packet is 41 bytes (slider count 0, empty
                // effect filter). Walk the sliders to find the true length.
                if (end - offset < 39) return -1
                val count = ((buffer[offset + 37].toInt() and 0xFF) shl 8) or
                        (buffer[offset + 38].toInt() and 0xFF)
                if (count > 64) return end - offset // malformed; hand over as-is
                var idx = offset + 39
                for (i in 0 until count) {
                    if (idx + 2 > end) return -1
                    val len = ((buffer[idx].toInt() and 0xFF) shl 8) or
                            (buffer[idx + 1].toInt() and 0xFF)
                    if (len > 64) return end - offset
                    idx += 2 + len + 4 // name + value
                }
                if (idx + 2 > end) return -1
                val effectLen = ((buffer[idx].toInt() and 0xFF) shl 8) or
                        (buffer[idx + 1].toInt() and 0xFF)
                if (effectLen > 128) return end - offset
                idx += 2 + effectLen
                if (idx > end) -1 else idx - offset
            }
            PacketType.RESOLUTION -> 5
            PacketType.CAMERA -> 1
            PacketType.EFFECT_FILTER, PacketType.CORRECTION_FILTER -> {
                // [type][len][name...]
                if (end - offset < 2) return -1
                val nameLen = buffer[offset + 1].toInt() and 0xFF
                if (2 + nameLen > end - offset) -1 else 2 + nameLen
            }
            PacketType.ROTATION -> 2
            PacketType.BITRATE -> 3
            PacketType.ADAPTIVE_BITRATE -> 5
            PacketType.STABILIZATION -> 2
            PacketType.FLASH -> 2
            PacketType.FOCUS -> 2
            PacketType.CODEC -> 2
            PacketType.FPS -> 2
            PacketType.ZOOM -> 5
            PacketType.SCREEN -> 2
            PacketType.FLIP -> 2
            else -> end - offset // unknown type: old chunk behaviour
        }
    }

    private fun handlePacket(type: Byte, packet: ByteArray) {
        val buffer = packet
        Logger.log("STREAM", "Packet type=0x%02x len=%d".format(type, packet.size))
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
        screenOffRequested = enabled
        if (enabled && !streamer.hasValidSurface()) {
            Logger.log("STREAM", "Screen blanking postponed: the surface is not ready yet")
            return
        }
        if (screenOff == enabled) {
            return
        }
        applyScreenOff(enabled)
    }

    private fun applyScreenOff(enabled: Boolean) {
        if (screenOff == enabled) {
            return
        }
        screenOff = enabled
        if (enabled) {
            blankedAt = SystemClock.elapsedRealtime()
        }
        viewBinding.blackoutView.visibility = if (enabled) View.VISIBLE else View.GONE

        val params = window.attributes
        // Not 0f: on this panel a zero backlight also stops the touch
        // controller from reporting taps, so the screen could not be woken by
        // touching it. A black overlay at minimum brightness looks the same on
        // an AMOLED panel and keeps touch alive.
        params.screenBrightness =
            if (enabled) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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
        // Changing the backlight makes the digitizer report a transient touch on
        // this device, so ignore events right after blanking.
        if (SystemClock.elapsedRealtime() - blankedAt < 800) {
            Logger.log("STREAM", "Ignoring a touch right after blanking")
            return
        }
        setScreenOff(false)
        connectionManager.sendScreenState(false)
    }
}
