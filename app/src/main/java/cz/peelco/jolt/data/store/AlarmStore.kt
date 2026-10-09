package cz.peelco.jolt.data.store

import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.repository.AlarmRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * Alarms as one JSON list. iOS uses SwiftData; a handful of rows read whole
 * on every change doesn't need a database here.
 */
class AlarmStore(
    store: KeyValueStore,
) : AlarmRepository {
    private val values = JsonValueStore(store, "cz.peelco.jolt.alarms", ListSerializer(Alarm.serializer()), emptyList())

    override val alarms: StateFlow<List<Alarm>> = values.flow

    override suspend fun save(alarm: Alarm) =
        values.update { list -> (list.filterNot { it.id == alarm.id } + alarm).sortedWith(compareBy({ it.hour }, { it.minute })) }

    override suspend fun delete(id: UUID) = values.update { list -> list.filterNot { it.id == id } }

    fun alarm(id: UUID): Alarm? = values.value.firstOrNull { it.id == id }
}
