package com.paradoxcat.karpropertymanager

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.gateWith(flagFlow: Flow<Boolean>): Flow<T> = flagFlow.flatMapLatest {
    if (it) {
        this
    } else {
        flowOf()
    }
}