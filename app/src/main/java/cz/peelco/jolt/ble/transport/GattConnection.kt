package cz.peelco.jolt.ble.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import cz.peelco.jolt.ble.BleLog
import cz.peelco.jolt.ble.Uuids
import cz.peelco.jolt.domain.model.toHexString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Everything a GATT operation can fail with, phrased for the log and the UI. */
sealed class BleException(
    message: String,
) : Exception(message) {
    class BluetoothUnavailable(
        message: String = "Bluetooth is off or unavailable.",
    ) : BleException(message)

    class PermissionDenied : BleException("Bluetooth permission denied.")

    class NotConnected : BleException("No device connected.")

    class Timeout(
        what: String,
    ) : BleException("Timed out: $what.")

    class CharacteristicNotFound(
        uuid: UUID,
    ) : BleException("Characteristic ${Uuids.short(uuid)} not found on this device.")

    class WriteTypeUnsupported(
        uuid: UUID,
        requested: String,
    ) : BleException("${Uuids.short(uuid)} doesn't accept a write$requested.")

    class GattError(
        operation: String,
        status: Int,
    ) : BleException("$operation failed (GATT status 0x${"%04X".format(status)}).")

    class ConnectFailed(
        detail: String,
    ) : BleException(detail)
}

/** How a write is sent; mirrors Android's write types. */
enum class WriteType { WITH_RESPONSE, WITHOUT_RESPONSE }

/**
 * One GATT link to one peripheral, with Android's quirks hidden behind
 * suspend functions.
 *
 * Android's GATT allows a single outstanding operation per connection and
 * silently drops a second one issued before the first's callback, so every
 * operation goes through [operations]. Callbacks arrive on a binder thread
 * and complete the one pending deferred.
 *
 * Callers must hold `BLUETOOTH_CONNECT` (API 31+); `CompositeDeviceRepository`
 * checks it before creating a connection.
 */
@SuppressLint("MissingPermission")
class GattConnection(
    private val context: Context,
    val device: BluetoothDevice,
) {
    sealed interface Link {
        data object Connecting : Link

        data object Connected : Link

        data class Disconnected(
            val status: Int,
        ) : Link
    }

    data class Notification(
        val service: UUID,
        val characteristic: UUID,
        val value: ByteArray,
    )

    private val linkState = MutableStateFlow<Link>(Link.Disconnected(0))
    val link: StateFlow<Link> = linkState.asStateFlow()

    private val notificationFlow = MutableSharedFlow<Notification>(extraBufferCapacity = 64)

    /** Every notification and indication from the peripheral, as they arrive. */
    val notifications: SharedFlow<Notification> = notificationFlow.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    private val operations = Mutex()

    @Volatile private var pending: CompletableDeferred<Result<ByteArray>>? = null

    @Volatile private var connectWaiter: CompletableDeferred<Result<Unit>>? = null

    /** Subscriber counts per characteristic, so one consumer can't unsubscribe another. */
    private val subscriberCounts = mutableMapOf<UUID, Int>()
    private val subscriptions = Mutex()

    val address: String get() = device.address

    // Connection.

    /**
     * Connects and discovers services. [autoConnect] waits for the device to
     * come into range for as long as it takes (the background reconnect); a
     * direct connect gives up after [timeout].
     */
    suspend fun connect(
        autoConnect: Boolean,
        timeout: Duration? = 15.seconds,
    ) {
        val waiter = CompletableDeferred<Result<Unit>>()
        connectWaiter = waiter
        linkState.value = Link.Connecting
        gatt?.close()
        gatt =
            device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw BleException.ConnectFailed("Couldn't open a connection to ${device.address}.")
        try {
            if (timeout != null) withTimeout(timeout) { waiter.await() }.getOrThrow() else waiter.await().getOrThrow()
        } catch (_: TimeoutCancellationException) {
            close()
            throw BleException.Timeout("connect to ${device.address}")
        }
        discoverServices()
    }

    private suspend fun discoverServices() {
        val gatt = gatt ?: throw BleException.NotConnected()
        perform("discover services", 10.seconds) { gatt.discoverServices() }
        BleLog.info("Discovered ${gatt.services.size} service(s) on ${device.address}")
    }

    /** Ends the link and releases the GATT client. */
    fun close() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        failPending(BleException.NotConnected())
        connectWaiter?.complete(Result.failure(BleException.NotConnected()))
        linkState.value = Link.Disconnected(0)
        subscriberCounts.clear()
    }

    val isConnected: Boolean get() = linkState.value == Link.Connected && gatt != null

    // Discovery.

    /** Every characteristic the device exposes, with its service. */
    fun characteristics(): List<BluetoothGattCharacteristic> = gatt?.services.orEmpty().flatMap { it.characteristics }

    private fun characteristic(
        service: UUID,
        characteristic: UUID,
    ): BluetoothGattCharacteristic {
        val gatt = gatt ?: throw BleException.NotConnected()
        // Look in the named service first, then anywhere: a firmware that moves
        // a characteristic to another service shouldn't read as "missing".
        return gatt.getService(service)?.getCharacteristic(characteristic)
            ?: gatt.services.firstNotNullOfOrNull { it.getCharacteristic(characteristic) }
            ?: throw BleException.CharacteristicNotFound(characteristic)
    }

    fun hasCharacteristic(
        service: UUID,
        characteristic: UUID,
    ): Boolean = runCatching { characteristic(service, characteristic) }.isSuccess

    // Reads and writes.

    suspend fun read(
        service: UUID,
        characteristic: UUID,
        timeout: Duration = 10.seconds,
    ): ByteArray {
        val target = characteristic(service, characteristic)
        val gatt = gatt ?: throw BleException.NotConnected()
        return perform("read ${Uuids.short(characteristic)}", timeout) { gatt.readCharacteristic(target) }
    }

    /**
     * Writes [data]. The type defaults to with-response when the
     * characteristic allows it, so a rejected payload surfaces as an error
     * instead of vanishing.
     */
    suspend fun write(
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
        type: WriteType? = null,
        timeout: Duration = 10.seconds,
    ) {
        val target = characteristic(service, characteristic)
        val resolved =
            resolveWriteType(target.properties, type)
                ?: throw BleException.WriteTypeUnsupported(characteristic, type?.let { if (it == WriteType.WITH_RESPONSE) " with response" else " without response" } ?: "")
        val androidType =
            if (resolved == WriteType.WITH_RESPONSE) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val gatt = gatt ?: throw BleException.NotConnected()
        BleLog.debug("Write ${Uuids.short(characteristic)} ← ${data.toHexString()}")
        perform("write ${Uuids.short(characteristic)}", timeout) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(target, data, androidType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                target.writeType = androidType
                @Suppress("DEPRECATION")
                target.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(target)
            }
        }
        if (resolved == WriteType.WITH_RESPONSE) BleLog.info("Write acknowledged by ${Uuids.short(characteristic)}")
    }

    // Notifications.

    /**
     * The values [characteristic] notifies, for as long as the flow is
     * collected. Subscriptions are counted, so the battery monitor, the poke
     * trigger and the diagnostics capture can all listen at once.
     */
    fun notificationsOf(
        service: UUID,
        characteristic: UUID,
        /** Called once the subscription is live, so a query can be sent without missing its answer. */
        onSubscribed: () -> Unit = {},
    ): Flow<ByteArray> =
        flow {
            setSubscribed(service, characteristic, true)
            try {
                notifications
                    .onSubscription { onSubscribed() }
                    .filter { it.characteristic == characteristic }
                    .map { it.value }
                    .collect { emit(it) }
            } finally {
                withContext(NonCancellable) { runCatching { setSubscribed(service, characteristic, false) } }
            }
        }

    /** Whether [characteristic] can notify or indicate at all. */
    fun canNotify(
        service: UUID,
        characteristic: UUID,
    ): Boolean =
        runCatching { characteristic(service, characteristic).properties }
            .getOrNull()
            ?.let { it and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 } ?: false

    private suspend fun setSubscribed(
        service: UUID,
        characteristic: UUID,
        subscribe: Boolean,
    ) {
        val target = characteristic(service, characteristic)
        val properties = target.properties
        val canNotify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val canIndicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!canNotify && !canIndicate) throw BleException.CharacteristicNotFound(characteristic)

        val needsWrite =
            subscriptions.withLock {
                val count = subscriberCounts[characteristic] ?: 0
                val next = if (subscribe) count + 1 else (count - 1).coerceAtLeast(0)
                subscriberCounts[characteristic] = next
                (subscribe && count == 0) || (!subscribe && count == 1)
            }
        if (!needsWrite) return

        val gatt = gatt ?: throw BleException.NotConnected()
        gatt.setCharacteristicNotification(target, subscribe)
        val descriptor = target.getDescriptor(Uuids.CLIENT_CHARACTERISTIC_CONFIG) ?: return
        val value =
            when {
                !subscribe -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                canNotify -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
        perform("${if (subscribe) "subscribe to" else "unsubscribe from"} ${Uuids.short(characteristic)}", 10.seconds) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    // The serial operation queue.

    /**
     * Runs one GATT operation: [start] issues it, the matching callback
     * completes [pending]. Holding [operations] for the whole round trip is
     * what keeps Android from dropping overlapping requests.
     */
    private suspend fun perform(
        what: String,
        timeout: Duration,
        start: () -> Boolean,
    ): ByteArray =
        operations.withLock {
            if (gatt == null) throw BleException.NotConnected()
            val deferred = CompletableDeferred<Result<ByteArray>>()
            pending = deferred
            try {
                if (!start()) throw BleException.ConnectFailed("Android refused to $what.")
                withTimeout(timeout) { deferred.await() }.getOrThrow()
            } catch (_: TimeoutCancellationException) {
                throw BleException.Timeout(what)
            } finally {
                pending = null
            }
        }

    private fun complete(result: Result<ByteArray>) {
        pending?.complete(result)
    }

    private fun failPending(error: Exception) {
        pending?.complete(Result.failure(error))
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        BleLog.info("Connected to ${gatt.device.address}")
                        linkState.value = Link.Connected
                        connectWaiter?.complete(Result.success(Unit))
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        BleLog.info("Disconnected from ${gatt.device.address} (status $status)")
                        val error = BleException.ConnectFailed("The device disconnected (status $status).")
                        connectWaiter?.complete(Result.failure(error))
                        failPending(error)
                        linkState.value = Link.Disconnected(status)
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                complete(if (status == BluetoothGatt.GATT_SUCCESS) Result.success(ByteArray(0)) else Result.failure(BleException.GattError("Service discovery", status)))
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                complete(if (status == BluetoothGatt.GATT_SUCCESS) Result.success(value) else Result.failure(BleException.GattError("Read", status)))
            }

            @Deprecated("Called below API 33 only")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                @Suppress("DEPRECATION")
                onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                complete(if (status == BluetoothGatt.GATT_SUCCESS) Result.success(ByteArray(0)) else Result.failure(BleException.GattError("Write", status)))
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                complete(if (status == BluetoothGatt.GATT_SUCCESS) Result.success(ByteArray(0)) else Result.failure(BleException.GattError("Subscribe", status)))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                notificationFlow.tryEmit(Notification(characteristic.service.uuid, characteristic.uuid, value.copyOf()))
            }

            @Deprecated("Called below API 33 only")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                @Suppress("DEPRECATION")
                onCharacteristicChanged(gatt, characteristic, characteristic.value ?: ByteArray(0))
            }
        }

    companion object {
        /**
         * The write type to use, or null when the characteristic allows
         * neither the requested one nor any. An explicit request is honoured
         * only when supported, never silently swapped.
         */
        fun resolveWriteType(
            properties: Int,
            requested: WriteType?,
        ): WriteType? {
            val withResponse = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val withoutResponse = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            return when (requested) {
                WriteType.WITH_RESPONSE -> WriteType.WITH_RESPONSE.takeIf { withResponse }
                WriteType.WITHOUT_RESPONSE -> WriteType.WITHOUT_RESPONSE.takeIf { withoutResponse }
                null ->
                    when {
                        withResponse -> WriteType.WITH_RESPONSE
                        withoutResponse -> WriteType.WITHOUT_RESPONSE
                        else -> null
                    }
            }
        }

        /** The property names Diagnostics shows, the same labels as iOS. */
        fun propertyLabels(properties: Int): List<String> =
            buildList {
                if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("broadcast")
                if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
                if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNoResp")
                if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
                if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
                if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
                if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("signedWrite")
                if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) add("extended")
            }
    }
}
