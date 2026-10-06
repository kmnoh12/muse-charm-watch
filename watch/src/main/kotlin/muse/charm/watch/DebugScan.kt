package muse.charm.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Debug only: lists what is advertising nearby (Muse gadgets in detail) so we can compare another device's
 * advertisement with the watch's own. Started with `am start ... --ez scan true`; results go to logcat tag MuseScan.
 */
@SuppressLint("MissingPermission")
object DebugScan {
    private const val TAG = "MuseScan"

    fun run(context: Context, seconds: Int = 12) {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) { Log.w(TAG, "no BLE scanner"); return }
        var others = 0
        val seen = HashSet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val uuids = record?.serviceUuids?.joinToString { it.uuid.toString() } ?: ""
                val name = record?.deviceName ?: result.device.name ?: ""
                val interesting = name.contains("Muse", ignoreCase = true) || uuids.contains("7fdd3d1c", ignoreCase = true)
                if (!interesting) { if (seen.add(result.device.address)) others++; return }
                val raw = record?.bytes?.joinToString("") { "%02x".format(it) } ?: ""
                Log.i(TAG, "MUSE name='$name' addr=${result.device.address} rssi=${result.rssi} connectable=${result.isConnectable} uuids=[$uuids] mfg=${record?.manufacturerSpecificData} raw=$raw")
            }

            override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed $errorCode") }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        Log.i(TAG, "scanning for $seconds s")
        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(callback)
            Log.i(TAG, "scan done; $others other devices seen")
        }, seconds * 1000L)
    }
}
