package com.speedevand.inkride.ble.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * GATT client that keeps live connections to the paired HRM/cadence sensors and
 * folds their notifications into a single [BleSample] flow. Permissions
 * (BLUETOOTH_CONNECT on 12+) are requested by the UI before a ride; calls here
 * are annotated [SuppressLint] accordingly and fail soft if the adapter is off.
 */
@SuppressLint("MissingPermission")
class AndroidBleSensorDataSource(
    private val context: Context
) : BleSensorDataSource {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val samples = MutableStateFlow(BleSample(timestampMs = 0L))

    // One GATT per connected address (an address may serve both HR and cadence).
    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val cadenceTrackers = ConcurrentHashMap<String, CscCadenceTracker>()

    // Per-address queue of characteristics still awaiting a CCCD-enable write.
    // Android GATT permits only one outstanding operation, so notifications are
    // enabled one at a time, advancing on each onDescriptorWrite. Accessed only
    // from the (serial) GATT callback thread.
    private val pendingNotifications = ConcurrentHashMap<String, ArrayDeque<BluetoothGattCharacteristic>>()

    // Desired addresses currently requested, so connect() can be idempotent.
    @Volatile
    private var connectedAddresses: Set<String> = emptySet()

    @Volatile
    private var latestHeartRate: Int? = null

    @Volatile
    private var latestCadence: Int? = null

    @Volatile
    private var latestWheelRevolutions: Long? = null

    override fun observeSamples(): Flow<BleSample> = samples

    override fun connect(hrmAddress: String?, cadenceAddress: String?) {
        val desired = setOfNotNull(hrmAddress, cadenceAddress)
        if (desired == connectedAddresses) return
        disconnect()
        if (desired.isEmpty()) return

        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) return

        connectedAddresses = desired
        desired.forEach { address ->
            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return@forEach
            cadenceTrackers[address] = CscCadenceTracker()
            val gatt = device.connectGatt(context, /* autoConnect = */ true, gattCallback)
            if (gatt != null) gatts[address] = gatt
        }
    }

    override fun disconnect() {
        gatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gatts.clear()
        cadenceTrackers.clear()
        pendingNotifications.clear()
        connectedAddresses = emptySet()
        latestHeartRate = null
        latestCadence = null
        latestWheelRevolutions = null
        emit()
    }

    private fun emit() {
        samples.value = BleSample(
            timestampMs = System.currentTimeMillis(),
            heartRateBpm = latestHeartRate,
            cadenceRpm = latestCadence,
            wheelRevolutions = latestWheelRevolutions
        )
    }

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val address = gatt.device?.address ?: return
            val queue = ArrayDeque<BluetoothGattCharacteristic>()
            gatt.getService(BleGatt.HEART_RATE_SERVICE)
                ?.getCharacteristic(BleGatt.HEART_RATE_MEASUREMENT)?.let { queue.add(it) }
            gatt.getService(BleGatt.CSC_SERVICE)
                ?.getCharacteristic(BleGatt.CSC_MEASUREMENT)?.let { queue.add(it) }
            pendingNotifications[address] = queue
            enableNextNotification(gatt, address)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // Previous CCCD write finished — enable the next characteristic, if any.
            gatt.device?.address?.let { enableNextNotification(gatt, it) }
        }

        @Deprecated("Deprecated in API 33; the pre-33 overload remains for broad device support")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristic(gatt.device?.address, characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristic(gatt.device?.address, characteristic.uuid, value)
        }
    }

    private fun handleCharacteristic(address: String?, uuid: java.util.UUID, data: ByteArray?) {
        if (data == null) return
        when (uuid) {
            BleGatt.HEART_RATE_MEASUREMENT -> {
                parseHeartRate(data)?.let {
                    latestHeartRate = it
                    emit()
                }
            }
            BleGatt.CSC_MEASUREMENT -> {
                val tracker = address?.let { cadenceTrackers[it] } ?: return
                val result = tracker.update(data) ?: return
                result.cadenceRpm?.let { latestCadence = it }
                result.wheelRevolutions?.let { latestWheelRevolutions = it }
                emit()
            }
        }
    }

    /**
     * Enables the next queued characteristic's notifications and writes its CCCD,
     * one at a time. Skips characteristics with no CCCD by recursing immediately.
     */
    private fun enableNextNotification(gatt: BluetoothGatt, address: String) {
        val queue = pendingNotifications[address] ?: return
        val characteristic = queue.removeFirstOrNull() ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(BleGatt.CCCD)
        if (descriptor == null) {
            enableNextNotification(gatt, address)
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }
}
