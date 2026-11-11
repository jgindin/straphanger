package com.straphanger.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SharedRouteInbox {
    private val _pendingSharedTexts = MutableStateFlow<List<String>>(emptyList())
    val pendingSharedTexts: StateFlow<List<String>> = _pendingSharedTexts.asStateFlow()

    fun enqueueSharedRouteText(text: String) {
        _pendingSharedTexts.value = _pendingSharedTexts.value + text
    }

    fun consumeAll(): List<String> {
        val current = _pendingSharedTexts.value
        _pendingSharedTexts.value = emptyList()
        return current
    }
}
