package com.dotocanet.peeklycam.networking.connection

import android.util.Log
import com.dotocanet.peeklycam.util.Logger
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrapper around a socket to manage the TCP connection
 * @param ipAddress Remote endpoint's IPv4 address
 * @param port Remote endpoint's port
 * @param listener The registered listener for callbacks
 */
class TCPConnection(
    ipAddress: String,
    port: Int,
    private val isOverAdb: Boolean,
    private val listener: Connection.Listener
) : Connection() {

    override val maxPacketSize = 65472

    private var socket: Socket
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private var thread: Thread
    private val running = AtomicBoolean(true)

    override val localIpAddress: String
        get() = socket.localAddress.hostAddress!!

    init {
        try {
            socket = Socket(ipAddress, port)

            outputStream = socket.getOutputStream()
            inputStream = socket.getInputStream()

            Logger.log("TCPConnection", "Connected to $ipAddress:$port")

            thread = Thread { startReceiveBytesLoop() }
            thread.start()
        } catch (e: Exception) {
            throw ConnectionFailedException("$ipAddress:$port", e.message ?: "")
        }
    }

    override fun send(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
    }

    override fun send(bytes: ByteArray, size: Int) {
        socket.getOutputStream().write(bytes, 0, size)
    }

    private fun startReceiveBytesLoop() {
        val buf = ByteArray(512)
        while (running.get()) {
            try {
                val bytes = inputStream?.read(buf) ?: -1
                // read() returns -1 at end of stream. Over Wi-Fi the socket can
                // be closed by the other side at any time; without this check
                // the loop spun at 100% CPU and kept re-delivering the stale
                // buffer contents to the listener (which, for example, re-ran
                // the screen-off command hundreds of times per second) until
                // the system killed the process.
                if (bytes <= 0) {
                    listener.onDisconnected()
                    break
                }
                listener.onBytesReceived(buf, bytes)
            } catch (e: Exception) {
                Logger.log("TCPConnection", "Error while reading. Closing socket. ${e.message}")
                listener.onDisconnected()
                break
            }
        }
    }

    override fun close() {
        running.set(false)
        socket.close()
        thread.join()
    }
}