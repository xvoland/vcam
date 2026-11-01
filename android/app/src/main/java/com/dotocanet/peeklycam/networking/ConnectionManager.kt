// (c) 2026, Vitalii Tereshchuk / xVoLAnD (https://dotoca.net) All rights reserved.
// Modifications to the original darusc/VCamdroid project (MIT License).

package com.dotocanet.peeklycam.networking

import com.dotocanet.peeklycam.networking.connection.Connection
import com.dotocanet.peeklycam.networking.connection.TCPConnection
import com.dotocanet.peeklycam.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConnectionManager private constructor() {

    private val TAG = "PeeklyCam"

    companion object {
        @Volatile private var instance: ConnectionManager? = null

        fun getInstance(): ConnectionManager {
            synchronized(this) {
                return instance ?: ConnectionManager().also { instance = it }
            }
        }

        fun getInstance(connectionStateCallback: ConnectionStateCallback): ConnectionManager {
            synchronized(this) {
                if(instance == null) {
                    instance = ConnectionManager()
                }
                instance!!.setConnectionStateCallback(connectionStateCallback)
                return instance!!
            }
        }
    }

    enum class Mode {
        USB,
        WIFI
    }

    interface ConnectionStateCallback {
        fun onConnectionSuccessful(connectionMode: Mode) { }
        fun onConnectionFailed(connectionMode: Mode) { }
        fun onDisconnected() { }
    }

    private val lock = Any()

    /**
     * Bumped on every connection attempt. A connection whose generation is no
     * longer current is stale: its reader thread may still be alive, but its
     * packets and its disconnect must be ignored.
     */
    @Volatile private var generation = 0

    val localIpAddress: String
        get() = tcpConn?.localIpAddress ?: "127.0.0.1"

    /** True while a socket is open (an attempt in progress does not count). */
    val isConnected: Boolean
        get() = synchronized(lock) { tcpConn != null }

    private var connectionStateCallback: ConnectionStateCallback? = null
    private var onBytesReceivedCallback: ((buffer: ByteArray, bytes: Int) -> Unit)? = null

    private var tcpConn: TCPConnection? = null

    private fun setConnectionStateCallback(connectionStateCallback: ConnectionStateCallback) {
        this.connectionStateCallback = connectionStateCallback
    }

    /**
     * The bytesReceivedListener is called only for packets of type different than PacketType.ACTIVATION.
     * These packets are handled internally
     *
     * Pass `null` when the receiving activity goes away: the manager is a
     * singleton and would otherwise call into a destroyed activity.
     */
    fun setOnBytesReceivedCallback(onBytesReceivedCallback: ((buffer: ByteArray, bytes: Int) -> Unit)?) {
        this.onBytesReceivedCallback = onBytesReceivedCallback
    }

    /**
     * Connect in WIFI mode.
     */
    fun connect(ipAddress: String, port: Int) = connectInternal(ipAddress, port, false)

    /**
     * Connect in USB mode (through adb)
     */
    fun connect(port: Int) = connectInternal("127.0.0.1", port, true)

    private fun connectInternal(ipAddress: String, port: Int, isOverAdb: Boolean) {
        val mode = if (isOverAdb) Mode.USB else Mode.WIFI

        CoroutineScope(Dispatchers.IO).launch {
            // Invalidate whatever came before and drop its socket. Two
            // connections used to coexist after a reconnect: the old reader
            // thread then reported "Socket closed" for the replaced session,
            // which closed the *new* socket and finished the new
            // StreamActivity, so the stream never appeared.
            val myGeneration: Int
            val previous: TCPConnection?
            synchronized(lock) {
                generation += 1
                myGeneration = generation
                previous = tcpConn
                tcpConn = null
            }
            previous?.let { conn ->
                Logger.log("CONNECTION MANAGER", "Replacing the previous connection")
                runCatching { conn.close() }
            }

            val listener = object : Connection.Listener {
                override fun onBytesReceived(buffer: ByteArray, bytes: Int) {
                    if (myGeneration != generation) {
                        return
                    }
                    onBytesReceivedCallback?.invoke(buffer, bytes)
                }

                override fun onDisconnected() {
                    if (myGeneration != generation) {
                        return
                    }
                    handleDisconnected(myGeneration)
                }
            }

            try {
                val connection = TCPConnection(ipAddress, port, isOverAdb, listener)
                val superseded = synchronized(lock) {
                    if (myGeneration != generation) {
                        true
                    } else {
                        tcpConn = connection
                        false
                    }
                }
                if (superseded) {
                    Logger.log("CONNECTION MANAGER", "Dropping a superseded connection to $ipAddress:$port")
                    runCatching { connection.close() }
                    return@launch
                }
                connectionStateCallback?.onConnectionSuccessful(mode)
            } catch (e: Connection.ConnectionFailedException) {
                if (myGeneration != generation) {
                    return@launch
                }
                Logger.log("CONNECTION MANAGER", "" + e.message)
                connectionStateCallback?.onConnectionFailed(mode)
            }
        }
    }

    /**
     * Closes the current connection without notifying the listener. Used when
     * the streaming activity is finished on purpose: the socket must not stay
     * open behind the pairing screen, or the next QR scan would open a second
     * session on top of a dead one.
     */
    fun disconnect() {
        val connection: TCPConnection?
        synchronized(lock) {
            generation += 1
            connection = tcpConn
            tcpConn = null
        }
        connection?.let { conn ->
            Logger.log("CONNECTION MANAGER", "Disconnected on request")
            runCatching { conn.close() }
        }
    }

    fun sendDescriptor(descriptor: DeviceDescriptor) {
        val bytes = descriptor.serialize()
        CoroutineScope(Dispatchers.IO).launch {
            // The socket can be closed by a reconnect at any moment; a failed
            // send must not take the process down.
            runCatching { tcpConn?.send(bytes) }
        }
    }

    fun sendErrorReport(report: ErrorReport) {
        val bytes = report.serialize()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { tcpConn?.send(bytes) }
        }
    }

    /**
     * Tells the desktop client the screen was woken up locally, so its
     * "screen off" checkbox can follow along. The protocol is otherwise
     * one-way (desktop -> phone) for control packets.
     */
    fun sendScreenState(off: Boolean) {
        val packet = byteArrayOf(PacketType.SCREEN, if (off) 1 else 0)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { tcpConn?.send(packet) }
        }
    }

    private fun handleDisconnected(fromGeneration: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            val connection = synchronized(lock) {
                // A reconnect may have started between the reader thread's
                // check and this callback: never tear down a newer session.
                if (fromGeneration != generation) {
                    return@launch
                }
                tcpConn.also { tcpConn = null }
            }
            connection?.let { runCatching { it.close() } }
            connectionStateCallback?.onDisconnected()
        }
    }
}
