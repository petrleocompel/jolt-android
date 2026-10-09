package cz.peelco.jolt.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The in-app Bluetooth log behind Diagnostics → Bluetooth log. Memory only,
 * the last [CAPACITY] lines of this launch, and mirrored to logcat.
 */
object BleLog {
    enum class Level { DEBUG, INFO, ERROR }

    data class Entry(
        val time: Instant,
        val level: Level,
        val message: String,
    ) {
        val timeText: String get() = TIME.format(time)
        val line: String get() = "$timeText  [${level.name.lowercase()}] $message"
    }

    const val CAPACITY = 500
    private const val TAG = "JoltBLE"

    /** Prefix of a device notification captured while listening. */
    const val EVENT_PREFIX = "EVENT "

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val state = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = state.asStateFlow()

    fun debug(message: String) = append(Level.DEBUG, message)

    fun info(message: String) = append(Level.INFO, message)

    fun error(message: String) = append(Level.ERROR, message)

    fun clear() {
        state.value = emptyList()
    }

    fun transcript(): String = state.value.joinToString("\n") { it.line }

    private fun append(
        level: Level,
        message: String,
    ) {
        runCatching {
            when (level) {
                Level.DEBUG -> Log.d(TAG, message)
                Level.INFO -> Log.i(TAG, message)
                Level.ERROR -> Log.e(TAG, message)
            }
        }
        state.update { (it + Entry(Instant.now(), level, message)).takeLast(CAPACITY) }
    }
}
