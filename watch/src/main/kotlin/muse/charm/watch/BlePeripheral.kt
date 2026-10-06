package muse.charm.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import muse.gadget.SetupTransport

/**
 * The BLE side of Muse pairing: advertises as `MuseGadgetXXXXXX` and exposes the setup service with
 * one characteristic the phone writes commands to (RX) and one the watch notifies answers on (TX).
 * UUIDs, advertisement contents and pacing follow the Linux SDK (ble_server.py).
 *
 * Android can only advertise the adapter's own name, so the watch's Bluetooth name is changed while
 * pairing and put back afterwards. All of this is untested on real hardware.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT are requested before start()
class BlePeripheral(
    private val context: Context,
    private val onWrite: (ByteArray) -> Unit,
    private val onClientDisconnected: () -> Unit,
) : SetupTransport {
    enum class State { STOPPED, STARTING, ADVERTISING, CONNECTED, UNSUPPORTED, ERROR }

    companion object {
        private const val TAG = "MuseBle"
        val SERVICE_UUID: UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
        val RX_UUID: UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
        val TX_UUID: UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Unassigned company id; the apps read the value as a "paired" flag (informational). */
        const val PAIRED_FLAG_COMPANY_ID = 0xFFFF
        private const val CHUNK_STAGGER_MS = 50L
        private const val DEFAULT_ATT_MTU = 23
    }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** A short reason when [state] is ERROR or UNSUPPORTED. */
    @Volatile var detail: String = ""
        private set

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var client: BluetoothDevice? = null
    @Volatile private var subscribed = false
    @Volatile private var mtu = DEFAULT_ATT_MTU
    private val notifyDone = Semaphore(0)
    private var originalName: String? = null
    private var advertising = false

    /** Starts advertising under [name]. Safe to call again. */
    fun start(name: String) {
        if (_state.value != State.STOPPED && _state.value != State.ERROR && _state.value != State.UNSUPPORTED) return
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) return fail(State.UNSUPPORTED, "Bluetooth is off or missing")
        if (adapter.bluetoothLeAdvertiser == null) return fail(State.UNSUPPORTED, "this device cannot advertise over BLE")
        _state.value = State.STARTING

        originalName = adapter.name
        if (adapter.name != name) adapter.name = name
        val gatt = manager.openGattServer(context, gattCallback) ?: return fail(State.ERROR, "could not open the GATT server")
        server = gatt

        val rx = BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val txChar = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        txChar.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        tx = txChar
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(rx)
        service.addCharacteristic(txChar)
        if (!gatt.addService(service)) fail(State.ERROR, "could not add the setup service")
        // Advertising starts in onServiceAdded.
    }

    fun stop() {
        val adapter = manager?.adapter
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
        runCatching { server?.close() }
        server = null; tx = null; client = null; subscribed = false; mtu = DEFAULT_ATT_MTU
        originalName?.let { name -> runCatching { if (adapter?.name != name) adapter?.name = name } }
        originalName = null
        _state.value = State.STOPPED
    }

    private fun fail(state: State, why: String) {
        Log.w(TAG, "$state: $why")
        detail = why
        _state.value = state
    }

    private fun startAdvertising() {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: return fail(State.UNSUPPORTED, "no BLE advertiser")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(PAIRED_FLAG_COMPANY_ID, byteArrayOf(0))
            .build()
        // The local name (MuseGadgetXXXXXX, the adapter name) goes in the scan response to fit the 31-byte limit.
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertising = true
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising as ${manager?.adapter?.name}")
            if (_state.value != State.CONNECTED) _state.value = State.ADVERTISING
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            fail(State.ERROR, "advertising failed (code $errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising() else fail(State.ERROR, "service add failed ($status)")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                client = device
                _state.value = State.CONNECTED
            } else {
                if (client?.address == device.address) {
                    client = null; subscribed = false; mtu = DEFAULT_ATT_MTU
                    notifyDone.release()
                    onClientDisconnected()
                    if (server != null) {
                        _state.value = State.ADVERTISING
                        if (!advertising) startAdvertising()
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            this@BlePeripheral.mtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val bytes = value ?: ByteArray(0)
            if (characteristic.uuid == RX_UUID) onWrite(bytes.copyOf())
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == CCCD_UUID && descriptor.characteristic.uuid == TX_UUID) {
                subscribed = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                Log.i(TAG, "client ${if (subscribed) "subscribed" else "unsubscribed"}")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyDone.release()
        }
    }

    // -- SetupTransport (called from the controller's message thread, never the main thread) --------

    override fun sendPackets(packets: List<ByteArray>) {
        val device = client ?: return
        val characteristic = tx ?: return
        val gatt = server ?: return
        if (!subscribed) return // answers travel as notifications; the phone subscribes before it writes
        for (packet in packets) {
            notifyDone.drainPermits()
            val queued = if (Build.VERSION.SDK_INT >= 33) {
                gatt.notifyCharacteristicChanged(device, characteristic, false, packet) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { characteristic.value = packet; gatt.notifyCharacteristicChanged(device, characteristic, false) }
            }
            if (!queued) {
                Log.w(TAG, "notify refused")
                return
            }
            notifyDone.tryAcquire(1, TimeUnit.SECONDS)
            Thread.sleep(CHUNK_STAGGER_MS)
        }
    }

    override fun mtu(): Int = mtu

    override fun disconnect(delayMs: Long) {
        main.postDelayed({ client?.let { runCatching { server?.cancelConnection(it) } } }, delayMs)
    }
}
