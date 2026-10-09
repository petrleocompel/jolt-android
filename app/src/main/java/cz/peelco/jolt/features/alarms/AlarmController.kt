package cz.peelco.jolt.features.alarms

import cz.peelco.jolt.data.store.AlarmStore
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.AlarmLocation
import cz.peelco.jolt.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Saving, deleting and toggling alarms: the store plus wherever the alarm
 * rings. The iOS `AlarmsViewModel` logic, shared by the Alarms tab and the
 * Remote dashboard's next-alarm card.
 */
class AlarmController(
    val store: AlarmStore,
    private val scheduler: AlarmScheduler,
    private val device: DeviceRepository,
) {
    private val error = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = error.asStateFlow()

    fun dismissError() {
        error.value = null
    }

    suspend fun save(alarm: Alarm) {
        val previous = store.alarm(alarm.id)
        try {
            store.save(alarm)
            // An alarm moved from the phone to the device must stop ringing here.
            if (previous?.location == AlarmLocation.PHONE && alarm.location != AlarmLocation.PHONE) scheduler.cancel(alarm.id)
            when (alarm.location) {
                AlarmLocation.PHONE -> scheduler.schedule(alarm)
                AlarmLocation.DEVICE -> device.syncDeviceAlarm(alarm)
            }
        } catch (failure: Exception) {
            error.value = failure.message
        }
    }

    suspend fun delete(alarm: Alarm) {
        try {
            store.delete(alarm.id)
            when (alarm.location) {
                AlarmLocation.PHONE -> scheduler.cancel(alarm.id)
                AlarmLocation.DEVICE -> device.deleteDeviceAlarm(alarm.id)
            }
        } catch (failure: Exception) {
            error.value = failure.message
        }
    }

    suspend fun toggle(alarm: Alarm) = save(alarm.copy(isEnabled = !alarm.isEnabled))

    fun snooze(alarm: Alarm) = scheduler.snooze(alarm)

    fun rescheduleAll() = scheduler.rescheduleAll(store.alarms.value)

    val canScheduleExact: Boolean get() = scheduler.canScheduleExact()
}
