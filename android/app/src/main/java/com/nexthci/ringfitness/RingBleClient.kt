package com.nexthci.ringfitness

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

data class ScannedRing(
    val address: String,
    val name: String,
    val rssi: Int,
)

@SuppressLint("MissingPermission")
class RingBleClient(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onBleState(message: String, ready: Boolean)
        fun onRingsFound(rings: List<ScannedRing>)
        fun onSensorPacket(packet: SensorPacket)
        fun onBleError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private val discoveredDevices = linkedMapOf<String, BluetoothDevice>()
    private val discoveredRings = linkedMapOf<String, ScannedRing>()
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var ready = false
    private var scanning = false
    private var writeWithResponse = false
    private var selectedDevice: BluetoothDevice? = null
    private var selectedName: String? = null
    private var autoReconnect = false
    private var stopped = false
    private var imuLowPower = false
    private var reconnectGeneration = 0
    private val reconnectRunnable = Runnable { connectSelectedDevice(isReconnect = true) }
    private val commandQueue = RingGattCommandQueue(
        schedule = { delay, action -> mainHandler.postDelayed({ action() }, delay) },
        write = ::writePacket,
        onReady = {
            ready = true
            listener.onBleState("戒指已连接", true)
        },
        onFailure = ::closeFailedControlConnection,
        trace = { Log.d(TAG, it) },
    )

    private val scanTimeout = Runnable {
        if (!scanning) return@Runnable
        stopScan()
        val rings = currentRings()
        listener.onRingsFound(rings)
        listener.onBleState(
            if (rings.isEmpty()) {
                "没有发现戒指，请确认戒指已唤醒且未连接其他设备"
            } else {
                "搜索完成，请选择要连接的戒指"
            },
            false,
        )
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::addScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mainHandler.removeCallbacks(scanTimeout)
            listener.onBleError("BLE 搜索失败：$errorCode")
            listener.onBleState("搜索失败，请点击按钮重试", false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (this@RingBleClient.gatt !== gatt) {
                gatt.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onBleState("已连接，正在发现戒指服务…", false)
                if (!gatt.discoverServices()) {
                    listener.onBleError("无法发现戒指服务")
                    gatt.disconnect()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handleDisconnected(gatt, "戒指连接已断开")
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onBleError("戒指连接失败：$status")
                gatt.disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this@RingBleClient.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onBleError("BLE 服务发现失败：$status")
                gatt.disconnect()
                return
            }
            val service = gatt.getService(UUID.fromString(RingProtocol.NUS_SERVICE_UUID))
            val notify = service?.getCharacteristic(UUID.fromString(RingProtocol.NUS_NOTIFY_UUID))
            writeCharacteristic = service?.getCharacteristic(UUID.fromString(RingProtocol.NUS_WRITE_UUID))
            if (notify == null || writeCharacteristic == null) {
                listener.onBleError("所选设备不是兼容的 Ringo 戒指")
                gatt.disconnect()
                return
            }
            val writeProperties = writeCharacteristic?.properties ?: 0
            writeWithResponse = when {
                writeProperties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> false
                writeProperties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> true
                else -> {
                    listener.onBleError("戒指写入特征不支持控制命令")
                    gatt.disconnect()
                    return
                }
            }
            Log.i(
                TAG,
                "Ring control characteristic properties=0x${writeProperties.toString(16)} " +
                    "writeMode=${if (writeWithResponse) "with-response" else "without-response"}",
            )
            if (!gatt.setCharacteristicNotification(notify, true)) {
                listener.onBleError("无法启用戒指数据通知")
                gatt.disconnect()
                return
            }
            val descriptor = notify.getDescriptor(CCCD_UUID)
            if (descriptor == null || !writeDescriptor(gatt, descriptor)) {
                listener.onBleError("无法启用戒指数据通知")
                gatt.disconnect()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (this@RingBleClient.gatt !== gatt) return
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onBleError("启用戒指数据通知失败：$status")
                gatt.disconnect()
                return
            }
            listener.onBleState("正在确认蓝牙传输参数…", false)
            commandQueue.beginMtu { gatt.requestMtu(247) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (this@RingBleClient.gatt !== gatt) return
            commandQueue.onMtuChanged(mtu, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (this@RingBleClient.gatt !== gatt) return
            handleNotification(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (this@RingBleClient.gatt !== gatt) return
            handleNotification(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (this@RingBleClient.gatt !== gatt || characteristic.uuid != writeCharacteristic?.uuid) return
            commandQueue.onWriteCompleted(status)
        }
    }

    fun start() {
        stopped = false
        val currentAdapter = adapter
        if (currentAdapter == null || !currentAdapter.isEnabled) {
            listener.onBleError("请先打开手机蓝牙")
            return
        }
        if (!ready && gatt == null && !scanning) {
            listener.onBleState("点击“搜索戒指”开始查找", false)
        }
    }

    fun scanForRings() {
        if (adapter?.isEnabled != true) {
            listener.onBleError("请先打开手机蓝牙")
            return
        }
        if (ready || gatt != null) return
        stopScan()
        discoveredDevices.clear()
        discoveredRings.clear()
        listener.onRingsFound(emptyList())
        listener.onBleState("正在搜索附近的 Ringo 戒指…", false)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        scanner?.startScan(null, settings, scanCallback)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun connect(address: String): Boolean {
        if (ready || gatt != null) return false
        val device = discoveredDevices[address] ?: return false
        stopScan()
        selectedDevice = device
        selectedName = discoveredRings[address]?.name ?: "Ringo"
        return connectSelectedDevice(isReconnect = false)
    }

    fun connectKnownAddress(address: String, name: String?): Boolean {
        if (ready || gatt != null) return false
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        selectedDevice = device
        selectedName = name ?: "Ringo"
        return connectSelectedDevice(isReconnect = true)
    }

    fun setAutoReconnect(enabled: Boolean) {
        autoReconnect = enabled
        if (enabled && !ready && gatt == null && selectedDevice != null) scheduleReconnect()
        if (!enabled) mainHandler.removeCallbacks(reconnectRunnable)
    }

    fun handleBluetoothUnavailable() {
        if (!ready && gatt == null) return
        ready = false
        writeCharacteristic = null
        commandQueue.reset()
        val currentGatt = gatt
        gatt = null
        currentGatt?.close()
        listener.onBleState("手机蓝牙不可用，正在等待恢复…", false)
        if (autoReconnect) scheduleReconnect()
    }

    fun retryAutoReconnectNow() {
        if (!autoReconnect || ready || gatt != null) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.post(reconnectRunnable)
    }

    fun forceReconnect(): Boolean {
        if (selectedDevice == null) return false
        autoReconnect = true
        ready = false
        reconnectGeneration += 1
        val generation = reconnectGeneration
        commandQueue.reset()
        writeCharacteristic = null
        listener.onBleState("正在关闭旧连接并重新连接戒指…", false)
        val currentGatt = gatt
        return if (currentGatt != null) {
            currentGatt.disconnect()
            mainHandler.postDelayed({
                if (generation != reconnectGeneration || gatt !== currentGatt) return@postDelayed
                Log.w(TAG, "GATT disconnect callback timed out; force closing stale connection")
                currentGatt.close()
                gatt = null
                writeCharacteristic = null
                listener.onBleState("旧连接关闭超时，正在重新建立戒指连接…", false)
                scheduleReconnect()
            }, FORCE_DISCONNECT_TIMEOUT_MS)
            true
        } else {
            scheduleReconnect()
            true
        }
    }

    fun forgetSelectedDevice() {
        autoReconnect = false
        mainHandler.removeCallbacks(reconnectRunnable)
        selectedDevice = null
        selectedName = null
    }

    fun stop() {
        stopped = true
        reconnectGeneration += 1
        autoReconnect = false
        mainHandler.removeCallbacks(reconnectRunnable)
        ready = false
        stopScan()
        commandQueue.reset()
        val currentGatt = gatt
        gatt = null
        writeCharacteristic = null
        currentGatt?.disconnect()
        currentGatt?.close()
    }

    fun isReady(): Boolean = ready

    fun startImu50Hz(): Boolean = startImu(RingProtocol.buildImuStart50Hz(), lowPower = false)
    fun startImu25Hz(): Boolean = startImu(RingProtocol.buildImuStart25Hz(), lowPower = false)
    fun startImuLowPower25Hz(): Boolean = startImu(RingProtocol.buildImuLowPowerStart25Hz(), lowPower = true)
    fun startImuLowPower50Hz(): Boolean = startImu(RingProtocol.buildImuLowPowerStart50Hz(), lowPower = true)
    fun stopImu(): Boolean = enqueue(RingProtocol.buildImuStop())
    fun startPpg(mode: PpgMode): Boolean = enqueue(RingProtocol.buildPpgStart(mode))
    fun stopPpg(): Boolean = enqueue(RingProtocol.buildPpgStop())
    fun requestBattery(): Boolean = enqueue(RingProtocol.buildBatteryGet())
    fun requestDeviceInfo(): Boolean = enqueue(RingProtocol.buildInfoGet())
    fun startHealth(): Boolean = enqueue(RingProtocol.buildHealthStart())
    fun stopHealth(): Boolean = enqueue(RingProtocol.buildHealthStop())
    fun requestHealthStatus(): Boolean = enqueue(RingProtocol.buildHealthStatusGet())
    fun requestHealthSessions(): Boolean = enqueue(RingProtocol.buildHealthList())
    fun readHealth(sessionId: Int, offset: Long, maxLength: Int = 0): Boolean =
        enqueue(RingProtocol.buildHealthRead(sessionId, offset, maxLength))

    private fun addScanResult(result: ScanResult) {
        val advertisedServices = result.scanRecord?.serviceUuids.orEmpty()
        val name = result.scanRecord?.deviceName ?: result.device.name
        val isRingo = name?.contains("Ringo", ignoreCase = true) == true ||
            advertisedServices.any { it.uuid.toString().equals(RingProtocol.NUS_SERVICE_UUID, true) }
        if (!isRingo) return
        val address = result.device.address
        val displayName = name ?: "Ringo ${address.takeLast(5)}"
        discoveredDevices[address] = result.device
        discoveredRings[address] = ScannedRing(address, displayName, result.rssi)
        Log.i(TAG, "Found $displayName at $address, RSSI ${result.rssi}")
        listener.onRingsFound(currentRings())
    }

    private fun currentRings(): List<ScannedRing> = discoveredRings.values.sortedByDescending { it.rssi }

    private fun handleNotification(value: ByteArray) {
        val command = value.firstOrNull()?.toInt()?.and(0xFF)
        if (command == 0x29 || command == 0x2A || command == 0x32) {
            Log.d(TAG, "RX control command=0x${command.toString(16)} bytes=${value.size}")
        }
        val packet = RingProtocol.parseNotification(
            value,
            System.currentTimeMillis(),
            imuLowPower = imuLowPower,
        ) ?: return
        if (packet is SensorPacket.Health) Log.d(TAG, "RX HEALTH ${packet.message}")
        listener.onSensorPacket(packet)
    }

    private fun startImu(packet: ByteArray, lowPower: Boolean): Boolean {
        if (!enqueue(packet)) return false
        imuLowPower = lowPower
        return true
    }

    private fun enqueue(packet: ByteArray): Boolean {
        if (!ready || gatt == null || writeCharacteristic == null) return false
        return commandQueue.enqueue(packet)
    }

    private fun writePacket(packet: ByteArray): Boolean {
        val currentGatt = gatt ?: return false
        val characteristic = writeCharacteristic ?: return false
        val writeType = if (writeWithResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val command = packet.firstOrNull()?.toInt()?.and(0xFF)
        if (command == 0x29 || command == 0x2A || command == 0x32) {
            Log.d(
                TAG,
                "TX control command=0x${command.toString(16)} " +
                    "subcommand=${packet.getOrNull(1)?.toInt()?.and(0xFF)} " +
                    "bytes=${packet.size} mode=${if (writeWithResponse) "with-response" else "without-response"}",
            )
        }
        val submitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(
                characteristic,
                packet,
                writeType,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = packet
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
        Log.d(TAG, "GATT control write accepted=$submitted")
        return submitted
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun connectSelectedDevice(isReconnect: Boolean): Boolean {
        if (stopped || ready || gatt != null) return false
        if (adapter?.isEnabled != true) {
            if (autoReconnect) scheduleReconnect()
            return false
        }
        val device = selectedDevice ?: return false
        mainHandler.removeCallbacks(reconnectRunnable)
        commandQueue.reset()
        listener.onBleState(
            if (isReconnect) "戒指已断开，正在自动重连 ${selectedName ?: "Ringo"}…"
            else "正在连接 ${selectedName ?: "Ringo"}…",
            false,
        )
        // Main-thread callbacks serialize GATT state, the command queue and its timeout handlers.
        // All existing public calls originate from Activity/Service main-thread handlers.
        gatt = device.connectGatt(
            appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, mainHandler,
        )
        return gatt != null
    }

    private fun handleDisconnected(disconnectedGatt: BluetoothGatt, message: String) {
        reconnectGeneration += 1
        ready = false
        writeCharacteristic = null
        commandQueue.reset()
        disconnectedGatt.close()
        if (gatt === disconnectedGatt) gatt = null
        listener.onBleState(
            if (autoReconnect) "$message，正在等待自动重连…" else message,
            false,
        )
        if (autoReconnect) scheduleReconnect()
    }

    private fun closeFailedControlConnection(message: String) {
        Log.w(TAG, message)
        listener.onBleError(message)
        // The command outcome can be unknown. Close this control channel; upper layers decide
        // whether to reconnect and reconcile STATUS. Never replay an accepted START here.
        val failedGatt = gatt
        ready = false
        writeCharacteristic = null
        commandQueue.reset()
        if (failedGatt != null) {
            runCatching { failedGatt.disconnect() }
            handleDisconnected(failedGatt, "蓝牙控制连接已关闭，请重新连接并核对状态")
        }
    }

    private fun scheduleReconnect() {
        if (stopped || !autoReconnect || selectedDevice == null) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
    }

    companion object {
        private const val TAG = "RingFitnessBle"
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val FORCE_DISCONNECT_TIMEOUT_MS = 1_500L
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
