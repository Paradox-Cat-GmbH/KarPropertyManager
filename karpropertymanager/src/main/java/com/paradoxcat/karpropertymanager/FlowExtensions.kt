package com.paradoxcat.karpropertymanager

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile

fun <T> Flow<T>.gateWith(flagFlow: Flow<Boolean>) =
    combine(flagFlow) { value, flag ->
        Pair(value, flag)
    }.takeWhile { it.second }.map { it.first }