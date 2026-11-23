package dev.vantara.replicatorops.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Replication queue: files waiting to print, in order. Kept on the phone and shared
 * between the app and Red Alert (same process), so the notification's Next button
 * and the app always see the same list. Queues are per printer.
 */
class ReplicationQueue private constructor(context: Context) {
    private val sp = context.getSharedPreferences("replication_queue", Context.MODE_PRIVATE)
    private val prefs = Prefs(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val _items = MutableStateFlow(load())
    /** File paths (gcodes root) for the active printer, next first. */
    val items: StateFlow<List<String>> = _items.asStateFlow()

    private fun key() = "queue_${prefs.activeId}"

    private fun load(): List<String> =
        sp.getString(key(), null)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()

    private fun save(list: List<String>) {
        _items.value = list
        sp.edit().putString(key(), json.encodeToString(list)).apply()
    }

    /** Reload after switching printers. */
    fun reload() { _items.value = load() }

    fun add(path: String) = save(_items.value + path)
    fun pushFront(path: String) = save(listOf(path) + _items.value)
    fun removeAt(index: Int) = save(_items.value.filterIndexed { i, _ -> i != index })
    fun clear() = save(emptyList())

    fun move(index: Int, by: Int) {
        val list = _items.value.toMutableList()
        val to = index + by
        if (index !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(index))
        save(list)
    }

    /** Takes the next file off the front of the queue. */
    fun pop(): String? {
        val list = _items.value
        if (list.isEmpty()) return null
        save(list.drop(1))
        return list.first()
    }

    companion object {
        @Volatile private var instance: ReplicationQueue? = null
        fun get(context: Context): ReplicationQueue =
            instance ?: synchronized(this) { instance ?: ReplicationQueue(context.applicationContext).also { instance = it } }
    }
}
