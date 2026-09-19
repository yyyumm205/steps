package com.nexthci.ringfitness

/** Read-only device queries for preparation. The Android adapter serializes all callbacks. */
interface PreparationTransport {
    fun connect(ring: PreparedRing, listener: Listener): Boolean
    fun disconnect()
    fun requestBattery(): Boolean
    fun requestDeviceInfo(): Boolean
    fun requestHealthStatus(): Boolean

    interface Listener {
        fun onConnection(message: String, ready: Boolean)
        fun onPacket(packet: SensorPacket)
        fun onError(message: String)
    }
}

data class RingPreparationState(
    val ring: PreparedRing? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val message: String = "未连接",
    val batteryPercent: Int? = null,
    val firmwareVersion: String? = null,
    val healthStatus: HealthMessage.Status? = null,
    val attemptId: Long = 0,
    val queryTimedOut: Boolean = false,
) {
    /** A status reply is required; a historical session ID alone is not an active recording. */
    val canPrepare: Boolean
        get() = connected && !connecting && healthStatus?.let {
            !it.collecting && it.errorCode == 0 && it.bytes == 0L && it.records == 0L
        } == true
}

/**
 * Keeps live device information separate from persisted preparation details.
 * This controller never starts/stops capture, reads stored records, or creates a session.
 * Call its methods and transport callbacks from one serial execution context.
 */
class RingPreparationController(
    private val transport: PreparationTransport,
    private val onChange: (RingPreparationState) -> Unit,
) {
    var state: RingPreparationState = RingPreparationState()
        private set

    private var generation = 0L

    fun connect(ring: PreparedRing) {
        val attempt = ++generation
        // Invalidate the previous listener before closing its connection.
        transport.disconnect()
        publish(RingPreparationState(ring = ring, connecting = true, message = "正在连接戒指…", attemptId = attempt))
        val listener = object : PreparationTransport.Listener {
            override fun onConnection(message: String, ready: Boolean) {
                if (attempt != generation) return
                if (!ready) {
                    if (state.connected) disconnect(message)
                    else publish(state.copy(message = message))
                    return
                }
                if (state.connected) return
                publish(state.copy(connected = true, connecting = false, message = "戒指已连接，正在读取设备状态…"))
                query(attempt)
            }

            override fun onPacket(packet: SensorPacket) {
                if (attempt != generation || !state.connected) return
                val next = when (packet) {
                    is SensorPacket.Battery -> state.copy(batteryPercent = packet.percent.takeIf { it in 0..100 })
                    is SensorPacket.Info -> state.copy(firmwareVersion = packet.firmwareVersion)
                    is SensorPacket.Health -> {
                        val status = packet.message as? HealthMessage.Status ?: return
                        state.copy(healthStatus = status, message = describe(status))
                    }
                    else -> return
                }
                // Replies can arrive after the UI timeout; clear it once all queries finish.
                publish(if (next.batteryPercent != null && next.firmwareVersion != null && next.healthStatus != null) {
                    next.copy(queryTimedOut = false, message = describe(next.healthStatus))
                } else next)
            }

            override fun onError(message: String) {
                if (attempt == generation) disconnect(message)
            }
        }
        val accepted = try {
            transport.connect(ring, listener)
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted && attempt == generation) disconnect("连接未成功，请检查蓝牙和权限后重试")
    }

    fun disconnect(message: String = "未连接") {
        ++generation
        publish(RingPreparationState(ring = state.ring, message = message, attemptId = generation))
        transport.disconnect()
    }

    /** A fresh connection gives refresh replies their own identity, excluding old queued replies. */
    fun refresh() {
        state.ring?.let(::connect)
    }

    fun timeout(attemptId: Long) {
        if (attemptId != generation) return
        if (!state.connected) {
            if (!state.connecting) return
            disconnect("连接超时，请唤醒戒指后重试")
            publish(state.copy(queryTimedOut = true))
            return
        }
        if (state.batteryPercent != null && state.firmwareVersion != null && state.healthStatus != null) return
        publish(state.copy(
            queryTimedOut = true,
            message = if (state.healthStatus == null) {
                "戒指状态读取超时，请刷新重试；确认状态后才能完成准备"
            } else {
                "${describe(requireNotNull(state.healthStatus))}；部分设备信息读取超时，可重新连接检查"
            },
        ))
    }

    private fun query(attempt: Long) {
        // Stop querying if an adapter reports an immediate failure or synchronously disconnects.
        val requests = listOf(transport::requestBattery, transport::requestDeviceInfo, transport::requestHealthStatus)
        for (request in requests) {
            if (attempt != generation || !state.connected) return
            val accepted = try {
                request()
            } catch (_: RuntimeException) {
                false
            }
            if (!accepted && attempt == generation) {
                disconnect("设备信息查询未成功，请重新连接后重试")
                return
            }
        }
    }

    private fun describe(status: HealthMessage.Status): String = when {
        status.errorCode != 0 -> "戒指状态异常（错误码 ${status.errorCode}），请联系研究者核对"
        status.collecting -> "戒指正在采集，请联系研究者核对当前记录"
        status.bytes > 0 || status.records > 0 -> "进入后自动保存戒指中的已有数据"
        status.bytes < 0 || status.records < 0 -> "戒指状态数据异常，请重新查询"
        else -> "戒指状态已确认，可以完成采集准备"
    }

    private fun publish(next: RingPreparationState) {
        state = next
        onChange(next)
    }
}
