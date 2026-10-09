package cz.peelco.jolt.ble

import android.bluetooth.BluetoothGattCharacteristic
import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.ble.legacy.LegacyDeviceController
import cz.peelco.jolt.ble.legacy.LegacyDeviceController.Command
import cz.peelco.jolt.ble.legacy.LegacyGatt
import cz.peelco.jolt.ble.transport.GattConnection
import cz.peelco.jolt.ble.transport.WriteType
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.AlarmLocation
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.Weekday
import org.junit.Test
import java.util.UUID

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

class LegacyProtocolTest {
    // What a live Pavlok 3 (fw 6.10.0) reads back on 1001, 1002 and 1003.
    private val vibeConfig = bytes(0x01, 0x0C, 0x23, 0x16, 0x16)
    private val beepConfig = bytes(0x01, 0x0C, 0x64, 0x16, 0x16)
    private val zapConfig = bytes(0x01, 0x19)

    @Test
    fun theServicesAndStimulusCharacteristicsMatchTheDecompiledMap() {
        assertThat(Uuids.canonical(LegacyGatt.CONFIG_SERVICE)).isEqualTo("156E1000-A300-4FEA-897B-86F698D74461")
        assertThat(Uuids.canonical(LegacyGatt.PAVLOK_SERVICE)).isEqualTo("156E0000-A300-4FEA-897B-86F698D74461")
        assertThat(Uuids.canonical(LegacyGatt.APPLICATION_SERVICE)).isEqualTo("156E5000-A300-4FEA-897B-86F698D74461")
        assertThat(Uuids.canonical(LegacyGatt.SETUP_SERVICE)).isEqualTo("156E7000-A300-4FEA-897B-86F698D74461")
        assertThat(Uuids.short(LegacyGatt.characteristic(StimulusKind.VIBE))).isEqualTo("1001")
        assertThat(Uuids.short(LegacyGatt.characteristic(StimulusKind.BEEP))).isEqualTo("1002")
        assertThat(Uuids.short(LegacyGatt.characteristic(StimulusKind.ZAP))).isEqualTo("1003")
    }

    @Test
    fun fireAndStoreDifferOnlyInTheFlag() {
        val zap = StimulusConfig(StimulusKind.ZAP, 25, 1)
        assertThat(LegacyDeviceController.payload(zap, zapConfig, Command.FIRE)).isEqualTo(bytes(0x81, 0x19))
        assertThat(LegacyDeviceController.payload(zap, zapConfig, Command.STORE)).isEqualTo(bytes(0x41, 0x19))
    }

    @Test
    fun zapPayloadIsCountThenLevel() {
        assertThat(LegacyDeviceController.payload(StimulusConfig(StimulusKind.ZAP, 40, 3), zapConfig, Command.FIRE)).isEqualTo(bytes(0x83, 0x28))
    }

    @Test
    fun fiveBytePayloadsPreserveTheConstantAndIntervals() {
        assertThat(LegacyDeviceController.payload(StimulusConfig(StimulusKind.VIBE, 60, 2), vibeConfig, Command.FIRE))
            .isEqualTo(bytes(0x82, 0x0C, 0x3C, 0x16, 0x16))
        assertThat(LegacyDeviceController.payload(StimulusConfig(StimulusKind.BEEP, 100, 1), beepConfig, Command.FIRE))
            .isEqualTo(bytes(0x81, 0x0C, 0x64, 0x16, 0x16))
    }

    @Test
    fun countCanNeverCarryIntoTheCommandFlag() {
        for (reps in listOf(1, 5, 63, 64, 127, 255)) {
            val payload = LegacyDeviceController.payload(StimulusConfig(StimulusKind.ZAP, 10, reps), zapConfig, Command.STORE)!!
            assertThat(payload[0].toInt() and 0xC0).isEqualTo(0x40)
        }
    }

    @Test
    fun anUnknownLayoutIsRefusedRatherThanGuessed() {
        for (length in listOf(0, 1, 3, 4, 6)) {
            assertThat(LegacyDeviceController.payload(StimulusConfig(StimulusKind.ZAP), ByteArray(length), Command.FIRE)).isNull()
        }
    }

    @Test
    fun alarmPayloadPacksTimeDaysAndId() {
        val alarm =
            Alarm(
                id = UUID.fromString("12345678-9abc-def0-1234-56789abcdef0"),
                location = AlarmLocation.DEVICE,
                hour = 7,
                minute = 30,
                repeatDays = setOf(Weekday.MONDAY, Weekday.SUNDAY),
            )
        assertThat(LegacyDeviceController.alarmPayload(alarm)).isEqualTo(bytes(7, 30, 0x41, 1, 0x12, 0x34, 0x56, 0x78))
    }
}

class TransportTest {
    private val write = BluetoothGattCharacteristic.PROPERTY_WRITE
    private val noResponse = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

    @Test
    fun writesPreferAResponseAndNeverSwapAnExplicitType() {
        assertThat(GattConnection.resolveWriteType(write or noResponse, null)).isEqualTo(WriteType.WITH_RESPONSE)
        assertThat(GattConnection.resolveWriteType(noResponse, null)).isEqualTo(WriteType.WITHOUT_RESPONSE)
        assertThat(GattConnection.resolveWriteType(noResponse, WriteType.WITH_RESPONSE)).isNull()
        assertThat(GattConnection.resolveWriteType(write, WriteType.WITHOUT_RESPONSE)).isNull()
        assertThat(GattConnection.resolveWriteType(0, null)).isNull()
    }

    @Test
    fun propertyLabelsMatchTheIosNames() {
        assertThat(GattConnection.propertyLabels(BluetoothGattCharacteristic.PROPERTY_READ or noResponse or BluetoothGattCharacteristic.PROPERTY_NOTIFY))
            .containsExactly("read", "writeNoResp", "notify")
            .inOrder()
    }

    @Test
    fun uuidFormsCompareRegardlessOfHowTheyWereWritten() {
        assertThat(Uuids.from("1001")).isEqualTo(UUID.fromString("00001001-0000-1000-8000-00805f9b34fb"))
        assertThat(Uuids.short(Uuids.from("2a19"))).isEqualTo("2A19")
        assertThat(Uuids.canonical("00002002-0000-1000-8000-00805f9b34fb")).isEqualTo("00002002-0000-1000-8000-00805F9B34FB")
        assertThat(Uuids.short(LegacyGatt.CONFIG_SERVICE)).isEqualTo("156E1000-A300-4FEA-897B-86F698D74461")
    }
}
