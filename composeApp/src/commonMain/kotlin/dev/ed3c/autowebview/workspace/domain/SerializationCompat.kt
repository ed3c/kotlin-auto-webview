package dev.ed3c.autowebview.workspace.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/** Keeps reified JSON decoding local to this package without widening global imports. */
internal inline fun <reified T> Json.decodeFromString(value: String): T =
    decodeFromString(serializer<T>(), value)
