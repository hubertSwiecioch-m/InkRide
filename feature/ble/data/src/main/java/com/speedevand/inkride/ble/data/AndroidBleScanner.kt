package com.speedevand.inkride.ble.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.ble.BleDevice
import com.speedevand.inkride.core.domain.ble.BleScanError
import com.speedevand.inkride.core.domain.ble.BleScanner
import com.speedevand.inkride.core.domain.ble.BleSensorType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Scans for HRM / cadence peripherals using the platform [android.bluetooth.le.BluetoothLeScanner].
 * Results are filtered to the relevant GATT service so the pairing UI only sees
 * compatible sensors.
 */
@SuppressLint("MissingPermission")
class AndroidBleScanner(
    private val context: Context,
) : BleScanner {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    override fun available(): EmptyResult<BleScanError> {
        val adapter = bluetoothManager?.adapter ?: return Result.Error(BleScanError.UNSUPPORTED)
        // Require CONNECT too: a paired sensor is useless if we can scan but can't
        // open a GATT connection, so block pairing rather than fail silently later.
        if (!hasScanPermission() || !hasConnectPermission()) return Result.Error(BleScanError.PERMISSION_DENIED)
        if (!adapter.isEnabled) return Result.Error(BleScanError.BLUETOOTH_OFF)
        if (adapter.bluetoothLeScanner == null) return Result.Error(BleScanError.UNSUPPORTED)
        return Result.Success(Unit)
    }

    override fun scan(type: BleSensorType): Flow<BleDevice> =
        callbackFlow {
            val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
            if (scanner == null || !hasScanPermission()) {
                close()
                return@callbackFlow
            }

            val serviceUuid =
                when (type) {
                    BleSensorType.HEART_RATE -> BleGatt.HEART_RATE_SERVICE
                    BleSensorType.CADENCE -> BleGatt.CSC_SERVICE
                }
            val filter =
                ScanFilter
                    .Builder()
                    .setServiceUuid(ParcelUuid(serviceUuid))
                    .build()
            val settings =
                ScanSettings
                    .Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        val device = result.device ?: return
                        trySend(
                            BleDevice(
                                address = device.address,
                                name = device.name ?: result.scanRecord?.deviceName,
                                type = type,
                            ),
                        )
                    }
                }

            scanner.startScan(listOf(filter), settings, callback)
            awaitClose { runCatching { scanner.stopScan(callback) } }
        }

    private fun hasScanPermission(): Boolean {
        // BLUETOOTH_SCAN is a runtime permission only on Android 12+. Earlier
        // versions rely on the (install-time) BLUETOOTH/BLUETOOTH_ADMIN pair.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean {
        // BLUETOOTH_CONNECT is a runtime permission only on Android 12+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
