package com.hjtk.app.android.carrier

import kotlinx.coroutines.flow.Flow

data class CarrierDelta(
    val text: String,
    val done: Boolean = false,
    val error: String? = null
)

data class CarrierStatus(val available: Boolean, val note: String)

/**
 * One way of reaching a model. The app does not care which is used; it cares
 * that the chosen one is honest about being unavailable.
 *
 * Implementations in the open build: REST carriers that the user enables with
 * their own key. The personal build adds a WebView session carrier behind the
 * same interface, so routing code does not change.
 */
interface AiCarrier {
    val id: String
    val label: String
    val requiresKey: Boolean
    suspend fun status(): CarrierStatus
    fun stream(prompt: String): Flow<CarrierDelta>
}
