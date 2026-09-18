package com.nexthci.ringfitness

import java.util.ArrayDeque

/**
 * One GATT control operation at a time, including the initial MTU exchange.
 * All methods and scheduled actions run on the same thread. A successful local write callback
 * releases the queue; it does not assert that the ring has executed a HEALTH command.
 */
internal class RingGattCommandQueue(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val write: (ByteArray) -> Boolean,
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val trace: (String) -> Unit = {},
) {
    private enum class Phase { CLOSED, MTU, READY }
    private enum class WritePhase { IDLE, CALLBACK, BACKOFF, GAP }

    private var phase = Phase.CLOSED
    private var writePhase = WritePhase.IDLE
    private val pending = ArrayDeque<ByteArray>()
    private var generation = 0L
    private var operation = 0L
    private var submissionFailures = 0

    /** Invalidates every delayed callback, including callbacks from a previous connection. */
    fun reset() {
        generation++
        operation++
        phase = Phase.CLOSED
        writePhase = WritePhase.IDLE
        submissionFailures = 0
        pending.clear()
    }

    fun beginMtu(request: () -> Boolean) {
        if (phase != Phase.CLOSED) return
        val attempt = generation
        phase = Phase.MTU
        trace("MTU request begin")
        val accepted = try {
            request()
        } catch (_: RuntimeException) {
            fail("蓝牙初始化请求失败，请检查蓝牙和权限后重新连接")
            return
        }
        if (attempt != generation || phase != Phase.MTU) return
        trace("MTU request accepted=$accepted")
        if (!accepted) {
            fail("蓝牙初始化失败：MTU 请求未受理，请重新连接")
            return
        }
        schedule(MTU_TIMEOUT_MS) {
            if (attempt == generation && phase == Phase.MTU) {
                fail("蓝牙初始化超时：未收到 MTU 确认，请重新连接")
            }
        }
    }

    fun onMtuChanged(mtu: Int, status: Int) {
        if (phase != Phase.MTU) return
        trace("MTU callback mtu=$mtu status=$status")
        if (status != 0 || mtu < 23) {
            fail("蓝牙初始化失败：MTU 确认异常（状态 $status），请重新连接")
            return
        }
        phase = Phase.READY
        onReady()
    }

    fun enqueue(packet: ByteArray): Boolean {
        if (phase != Phase.READY) return false
        pending.addLast(packet.copyOf())
        pump()
        return true
    }

    /** Used for both WRITE and WRITE_NO_RESPONSE: both have a local Android GATT callback. */
    fun onWriteCompleted(status: Int) {
        if (phase != Phase.READY || writePhase != WritePhase.CALLBACK) return
        trace("write callback status=$status")
        if (status != 0) {
            // START may already have taken effect. Never blindly submit an accepted command again.
            fail("BLE 控制命令写入失败（状态 $status），请重新连接并核对戒指状态")
            return
        }
        pending.removeFirst()
        submissionFailures = 0
        writePhase = WritePhase.GAP
        later(WRITE_GAP_MS) {
            writePhase = WritePhase.IDLE
            pump()
        }
    }

    private fun pump() {
        if (phase != Phase.READY || writePhase != WritePhase.IDLE || pending.isEmpty()) return
        writePhase = WritePhase.CALLBACK
        val attempt = generation
        val currentOperation = ++operation
        val packet = pending.first
        trace("write submit command=${packet.firstOrNull()?.toInt()?.and(255)} " +
            "subcommand=${packet.getOrNull(1)?.toInt()?.and(255)} attempt=${submissionFailures + 1}")
        val accepted = try {
            write(packet)
        } catch (_: RuntimeException) {
            fail("BLE 控制命令提交异常，请检查蓝牙和权限后重新连接并核对状态")
            return
        }
        // The adapter is asynchronous; also make synchronous test doubles/cancellation harmless.
        if (attempt != generation || currentOperation != operation || writePhase != WritePhase.CALLBACK) return
        if (accepted) {
            later(WRITE_CALLBACK_TIMEOUT_MS) {
                if (writePhase == WritePhase.CALLBACK) {
                    fail("BLE 控制命令确认超时，请重新连接并核对戒指状态")
                }
            }
        } else {
            submissionFailures++
            if (submissionFailures > WRITE_SUBMIT_RETRY_LIMIT) {
                fail("BLE 控制命令连续发送失败，请重新连接")
                return
            }
            // Keep the queue blocked during backoff; new enqueues cannot spend this retry budget.
            writePhase = WritePhase.BACKOFF
            later(WRITE_SUBMIT_RETRY_MS) {
                writePhase = WritePhase.IDLE
                pump()
            }
        }
    }

    private fun later(delay: Long, action: () -> Unit) {
        val attempt = generation
        val currentOperation = ++operation
        schedule(delay) {
            if (attempt == generation && currentOperation == operation) action()
        }
    }

    private fun fail(message: String) {
        trace(message)
        reset()
        onFailure(message)
    }

    companion object {
        internal const val MTU_TIMEOUT_MS = 5_000L
        internal const val WRITE_CALLBACK_TIMEOUT_MS = 5_000L
        internal const val WRITE_GAP_MS = 40L
        internal const val WRITE_SUBMIT_RETRY_MS = 120L
        internal const val WRITE_SUBMIT_RETRY_LIMIT = 3
    }
}
