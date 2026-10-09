package cz.peelco.jolt.ble.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import cz.peelco.jolt.ble.BleLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The phone side of Bluetooth: adapter state, permissions and scanning. The
 * Android counterpart of iOS's `BluetoothCentralManager`, minus the GATT work,
 * which lives in [GattConnection].
 */
@SuppressLint("MissingPermission")
class BleCentral(
    private val context: Context,
) {
    enum class AdapterState { ON, OFF, UNSUPPORTED, UNAUTHORIZED }

    /** A peripheral found by a scan, or one the phone already knows. */
    data class Discovery(
        val device: BluetoothDevice,
        val name: String?,
        val rssi: Int?,
        /** Already connected or bonded: it may not be advertising at all. */
        val wasAlreadyKnown: Boolean,
    )

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    fun currentState(): AdapterState =
        when {
            adapter == null -> AdapterState.UNSUPPORTED
            !hasConnectPermission() -> AdapterState.UNAUTHORIZED
            adapter?.isEnabled == true -> AdapterState.ON
            else -> AdapterState.OFF
        }

    /** Adapter state changes, starting with the current one. */
    fun stateUpdates(): Flow<AdapterState> =
        callbackFlow {
            trySend(currentState())
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        trySend(currentState())
                    }
                }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            awaitClose { context.unregisterReceiver(receiver) }
        }.distinctUntilChanged()

    /**
     * Waits briefly for the adapter to be on. Right after boot or a toggle it
     * can be off for a moment, and giving up at once strands the reconnect.
     */
    suspend fun waitUntilOn(timeout: Duration = 5.seconds): Boolean =
        withTimeoutOrNull(timeout) { stateUpdates().first { it == AdapterState.ON } } != null

    /** The peripheral for a remembered address. */
    fun device(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    /**
     * Matching peripherals as they turn up. Devices the phone is already
     * connected to, or bonded with, are yielded first: a connected device has
     * stopped advertising and would never appear in a scan.
     */
    fun scan(seedAddresses: List<String>): Flow<Discovery> =
        callbackFlow {
            val adapter = adapter
            val scanner = adapter?.bluetoothLeScanner
            if (adapter == null || scanner == null || !adapter.isEnabled) {
                BleLog.error("Scan not started: Bluetooth is off or unavailable")
                close(BleException.BluetoothUnavailable())
                return@callbackFlow
            }

            val known =
                buildList {
                    manager?.getConnectedDevices(BluetoothProfile.GATT)?.let(::addAll)
                    adapter.bondedDevices?.let(::addAll)
                    seedAddresses.mapNotNull(::device).let(::addAll)
                }.distinctBy { it.address }
            known.forEach { trySend(Discovery(it, it.name, null, wasAlreadyKnown = true)) }

            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        trySend(Discovery(result.device, result.scanRecord?.deviceName ?: result.device.name, result.rssi, wasAlreadyKnown = false))
                    }

                    override fun onScanFailed(errorCode: Int) {
                        BleLog.error("Scan failed with error $errorCode")
                        close(BleException.ConnectFailed("Scanning failed (error $errorCode)."))
                    }
                }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            BleLog.info("Scanning for Pavlok devices")
            scanner.startScan(null, settings, callback)
            awaitClose {
                runCatching { scanner.stopScan(callback) }
                BleLog.info("Scan stopped")
            }
        }
}
