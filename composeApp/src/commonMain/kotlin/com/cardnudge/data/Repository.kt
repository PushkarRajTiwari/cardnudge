package com.cardnudge.data

import com.cardnudge.domain.RewardCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Tiny key-value persistence: SharedPreferences on Android, NSUserDefaults on iOS. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
}

/**
 * Single source of truth for the user's cards. One instance per process, holding no
 * platform UI objects, so screens can come and go without leaking anything.
 */
class Repository(private val store: KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val cardList = ListSerializer(RewardCard.serializer())

    private val _cards = MutableStateFlow(load(KEY_CARDS, cardList))
    val cards: StateFlow<List<RewardCard>> = _cards.asStateFlow()

    fun saveCard(card: RewardCard) {
        _cards.update { list ->
            val i = list.indexOfFirst { it.id == card.id }
            if (i >= 0) list.toMutableList().also { it[i] = card } else list + card
        }
        store.putString(KEY_CARDS, json.encodeToString(cardList, _cards.value))
    }

    fun deleteCard(id: String) {
        _cards.update { list -> list.filterNot { it.id == id } }
        store.putString(KEY_CARDS, json.encodeToString(cardList, _cards.value))
    }

    private fun <T> load(key: String, serializer: KSerializer<List<T>>): List<T> =
        store.getString(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val KEY_CARDS = "cards"
    }
}
