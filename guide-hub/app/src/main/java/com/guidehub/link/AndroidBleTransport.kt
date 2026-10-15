package com.guidehub.link

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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.guidehub.core.Constants
import com.guidehub.core.LinkState
import com.guidehub.core.Permissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Plain [BluetoothGatt] wrapper. Scans for [serviceUuid] (or connects to a known address), requests a
 * larger MTU, discovers services and enables notifications on [notifyCharacteristics].
 * GATT operations are serialised: Android allows only one outstanding operation per connection.
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport(
    private val context: Context,
    override val name: String,
    private val serviceUuid: UUID,
    private val notifyCharacteristics: List<UUID>,
    private val addressProvider: () -> String?,
) : BleTransport {

    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _notifications = MutableSharedFlow<BleNotification>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val notifications: SharedFlow<BleNotification> = _notifications.asSharedFlow()

    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()
    private var pendingOp: CompletableDeferred<Any?>? = null
    private var connectResult: CompletableDeferred<Boolean>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestMtu(Constants.BLE_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _state.value = LinkState.DISCONNECTED
                connectResult?.complete(false)
                pendingOp?.complete(null)
                g.close()
                if (gatt == g) gatt = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS && g.getService(serviceUuid) != null
            connectResult?.complete(ok)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pendingOp?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Pre-API 33 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            pendingOp?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(BleNotification(c.uuid, value, System.currentTimeMillis()))
        }

        @Deprecated("Pre-API 33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            _notifications.tryEmit(BleNotification(c.uuid, c.value.copyOf(), System.currentTimeMillis()))
        }
    }

    override suspend fun connect(): Boolean {
        if (!Permissions.hasBle(context)) return false
        val ad = adapter ?: return false
        if (!ad.isEnabled) return false
        _state.value = LinkState.CONNECTING
        val device = addressProvider()?.let { runCatching { ad.getRemoteDevice(it) }.getOrNull() } ?: scan(ad)
        if (device == null) { _state.value = LinkState.DISCONNECTED; return false }

        val result = CompletableDeferred<Boolean>()
        connectResult = result
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(15_000) { result.await() } ?: false
        if (!ok) { disconnect(); return false }
        for (uuid in notifyCharacteristics) enableNotify(uuid)
        _state.value = LinkState.CONNECTED
        return true
    }

    private suspend fun scan(ad: BluetoothAdapter): BluetoothDevice? {
        val scanner = ad.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { found.complete(result.device) }
            override fun onScanFailed(errorCode: Int) { found.complete(null) }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        val device = withTimeoutOrNull(10_000) { found.await() }
        scanner.stopScan(cb)
        return device
    }

    private suspend fun enableNotify(uuid: UUID): Boolean {
        val g = gatt ?: return false
        val c = g.getService(serviceUuid)?.getCharacteristic(uuid) ?: return false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Constants.CCCD) ?: return false
        return op {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        } == true
    }

    override fun disconnect() {
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        _state.value = LinkState.DISCONNECTED
    }

    override suspend fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean {
        val g = gatt ?: return false
        val c = g.getService(serviceUuid)?.getCharacteristic(characteristic) ?: return false
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return op {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } == true
    }

    override suspend fun read(characteristic: UUID): ByteArray? {
        val g = gatt ?: return null
        val c = g.getService(serviceUuid)?.getCharacteristic(characteristic) ?: return null
        return op { g.readCharacteristic(c) } as? ByteArray
    }

    /** Runs one GATT operation and waits for its callback (or 2 s). */
    private suspend fun op(start: () -> Boolean): Any? = opMutex.withLock {
        val d = CompletableDeferred<Any?>()
        pendingOp = d
        if (!start()) { pendingOp = null; return@withLock null }
        val r = withTimeoutOrNull(2_000) { d.await() }
        pendingOp = null
        r
    }
}
