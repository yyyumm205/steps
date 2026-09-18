package com.nexthci.ringfitness

import android.content.Context
import android.os.Handler
import android.os.Looper

/** A fresh BLE client per attempt keeps old GATT callbacks out of the preparation screen. */
class AndroidPreparationTransport(context: Context) : PreparationTransport {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var client: RingBleClient? = null
    private var generation = 0L

    override fun connect(ring: PreparedRing, listener: PreparationTransport.Listener): Boolean {
        disconnect()
        val attempt = generation
        val next = RingBleClient(context, object : RingBleClient.Listener {
            private fun deliver(block: () -> Unit) {
                handler.post { if (attempt == generation && client != null) block() }
            }
            override fun onBleState(message: String, ready: Boolean) =
                deliver { listener.onConnection(message, ready) }
            override fun onSensorPacket(packet: SensorPacket) = deliver { listener.onPacket(packet) }
            override fun onBleError(message: String) = deliver { listener.onError(message) }
            override fun onRingsFound(rings: List<ScannedRing>) = Unit
        })
        client = next
        return next.connectKnownAddress(ring.address, ring.name)
    }

    override fun disconnect() {
        ++generation
        val previous = client
        client = null
        // Permissions can be revoked while the Activity is paused.
        runCatching { previous?.stop() }
    }

    override fun requestBattery() = client?.requestBattery() ?: false
    override fun requestDeviceInfo() = client?.requestDeviceInfo() ?: false
    override fun requestHealthStatus() = client?.requestHealthStatus() ?: false
}
