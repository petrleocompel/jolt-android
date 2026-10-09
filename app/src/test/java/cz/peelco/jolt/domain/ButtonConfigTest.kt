package cz.peelco.jolt.domain

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.domain.model.ButtonAction
import cz.peelco.jolt.domain.model.ButtonActionRecord
import cz.peelco.jolt.domain.model.ButtonConfigReport
import cz.peelco.jolt.domain.model.DeviceButtonSlot
import org.junit.Test

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

class ButtonActionRecordTest {
    @Test
    fun recordLengthsMatchTheFirmware() {
        val expected =
            mapOf(
                0x01 to 6, 0x02 to 6, 0x03 to 3, 0x05 to 2, 0x0B to 5, 0x0C to 5,
                0x0E to 3, 0x10 to 2, 0x11 to 4, 0x12 to 3, 0xFF to 1,
            )
        expected.forEach { (action, length) -> assertThat(ButtonActionRecord.lengthForAction(action)).isEqualTo(length) }
    }

    @Test
    fun unlistedActionsBelowTheCeilingAreOneByte() {
        assertThat(ButtonActionRecord.lengthForAction(0x00)).isEqualTo(1)
        assertThat(ButtonActionRecord.lengthForAction(0x06)).isEqualTo(1)
        assertThat(ButtonActionRecord.lengthForAction(0x0D)).isEqualTo(1)
    }

    @Test
    fun actionsAboveTheCeilingAreRejected() {
        assertThat(ButtonActionRecord.lengthForAction(0x13)).isEqualTo(0)
        assertThat(ButtonActionRecord.isWritable(0x13)).isFalse()
        assertThat(ButtonActionRecord.lengthForAction(0x20)).isEqualTo(0)
        assertThat(ButtonActionRecord.isWritable(0xFF)).isTrue()
    }

    @Test
    fun stopwatchAndTimerAreDistinguishedByTheirTail() {
        assertThat(ButtonActionRecord(bytes(0x11, 0x02, 0x10, 0x01)).action).isEqualTo(ButtonAction.STOP_WATCH)
        assertThat(ButtonActionRecord(bytes(0x11, 0x02, 0x10, 0x02)).action).isEqualTo(ButtonAction.TIMER)
    }

    @Test
    fun firmwareDefaultRecordsDecode() {
        assertThat(ButtonActionRecord(bytes(0x01, 0x01, 0x02, 0x50, 0x16, 0x16)).action).isEqualTo(ButtonAction.VIBRATE)
        assertThat(ButtonActionRecord(bytes(0x03, 0x01, 0x1E)).action).isEqualTo(ButtonAction.ZAP)
        assertThat(ButtonActionRecord(bytes(0xFF)).action).isEqualTo(ButtonAction.DISABLED)
        assertThat(ButtonActionRecord(bytes(0x10, 0x00)).action).isEqualTo(ButtonAction.FIND_MY_PHONE)
    }
}

class ButtonConfigReportTest {
    @Test
    fun headerThenRecordAttributesTheActionToThatButton() {
        val report = ButtonConfigReport.parse(listOf(bytes(0xE1, 0x04, 0x01), bytes(0x10, 0x00)))
        assertThat(report.actions[DeviceButtonSlot.TOP_LONG]).isEqualTo(ButtonAction.FIND_MY_PHONE)
        assertThat(report.unparsed).isEmpty()
    }

    @Test
    fun eachButtonInABurstKeepsItsOwnRecord() {
        val report =
            ButtonConfigReport.parse(
                listOf(
                    bytes(0xE1, 0x01, 0x01), bytes(0x01, 0x01, 0x02, 0x50, 0x16, 0x16),
                    bytes(0xE1, 0x04, 0x01), bytes(0x10, 0x00),
                    bytes(0xE1, 0x03, 0x01), bytes(0xFF),
                ),
            )
        assertThat(report.actions[DeviceButtonSlot.TOP]).isEqualTo(ButtonAction.VIBRATE)
        assertThat(report.actions[DeviceButtonSlot.TOP_LONG]).isEqualTo(ButtonAction.FIND_MY_PHONE)
        assertThat(report.actions[DeviceButtonSlot.BOTTOM]).isEqualTo(ButtonAction.DISABLED)
    }

    @Test
    fun aRecordCarryingOneLeadingFramingByteStillDecodes() {
        val report = ButtonConfigReport.parse(listOf(bytes(0xE1, 0x04, 0x01), bytes(0x99, 0x10, 0x00)))
        assertThat(report.actions[DeviceButtonSlot.TOP_LONG]).isEqualTo(ButtonAction.FIND_MY_PHONE)
    }

    @Test
    fun aFrameThatIsAlreadyAValidRecordIsNotReinterpreted() {
        val report = ButtonConfigReport.parse(listOf(bytes(0xE1, 0x04, 0x01), bytes(0x06, 0x10, 0x00)))
        assertThat(report.actions[DeviceButtonSlot.TOP_LONG]).isEqualTo(ButtonAction.TOGGLE_CANDLE)
    }

    @Test
    fun headersOutsideTheButtonRangeStrandTheirRecords() {
        val report = ButtonConfigReport.parse(listOf(bytes(0xE1, 0x09, 0x01), bytes(0x11, 0x02, 0x10, 0x01)))
        assertThat(report.actions).isEmpty()
        assertThat(report.unparsed).hasSize(1)
    }

    @Test
    fun noFramesMeansAnEmptyReport() {
        val report = ButtonConfigReport.parse(emptyList())
        assertThat(report.isEmpty).isTrue()
        assertThat(report.actions).isEmpty()
    }
}

class ButtonPayloadTest {
    @Test
    fun findMyPhoneCarriesItsTrailingByte() {
        assertThat(ButtonAction.FIND_MY_PHONE.payload(DeviceButtonSlot.TOP)).isEqualTo(bytes(0x02, 0x01, 0x10, 0x00))
    }

    @Test
    fun eachWritableActionHasTheLengthTheFirmwareExpects() {
        for (action in ButtonAction.entries) {
            val payload = action.payload(DeviceButtonSlot.MIDDLE) ?: continue
            if (action == ButtonAction.DEFAULT_ACTION || action == ButtonAction.TOGGLE_SLEEP_TRACKING) continue
            val record = payload.copyOfRange(2, payload.size)
            assertThat(record.size).isEqualTo(ButtonActionRecord.lengthForAction(record[0].toInt() and 0xFF))
        }
    }

    @Test
    fun theButtonByteIsTheSecondByte() {
        for (slot in DeviceButtonSlot.configurable) {
            assertThat(ButtonAction.DISABLED.payload(slot)!![1].toInt()).isEqualTo(slot.wireValue)
        }
    }

    @Test
    fun stimulusAndUntracedActionsAreRefused() {
        listOf(ButtonAction.ZAP, ButtonAction.BEEP, ButtonAction.VIBRATE, ButtonAction.AIRPLANE_MODE, ButtonAction.DO_NOT_DISTURB)
            .forEach { assertThat(it.payload(DeviceButtonSlot.TOP)).isNull() }
    }

    @Test
    fun deviceDefaultIsThreeBytes() {
        assertThat(ButtonAction.DEFAULT_ACTION.payload(DeviceButtonSlot.BOTTOM)).isEqualTo(bytes(0x02, 0x03, 0x00))
    }

    @Test
    fun backLongIsNotDecodedFromTheWire() {
        assertThat(DeviceButtonSlot.fromWireValue(0x07)).isNull()
        assertThat(DeviceButtonSlot.fromWireValue(0x04)).isEqualTo(DeviceButtonSlot.TOP_LONG)
    }
}
