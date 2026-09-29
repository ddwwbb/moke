package com.briqt.moke.ui

import com.briqt.moke.data.ExtraKeysLayout

/** Each layout has two rows of six editable slots; the seventh slot in each row is an action. */
const val EDITABLE_KEY_COUNT = 12

private val defaultKeys = DEFAULT_EXTRA_KEYS.flatten().filterNot { it is ExtraKey.Action }

/** Stable labels serve as persisted IDs; they are unique within the available key catalog. */
val EXTRA_KEY_CATALOG: List<ExtraKey> = (
    defaultKeys + KEY_SECTIONS.flatMap { it.rows.flatten() }
).distinctBy { it.label }
private val keysById = EXTRA_KEY_CATALOG.associateBy { it.label }

val DEFAULT_KEY_IDS: List<String> = defaultKeys.map { it.label }

fun validCustomKeyIds(ids: List<String>): List<String> =
    if (ids.size == EDITABLE_KEY_COUNT && ids.all(keysById::containsKey)) ids else DEFAULT_KEY_IDS

fun layoutKeyIds(layout: ExtraKeysLayout, customIds: List<String>): List<String> = when (layout) {
    ExtraKeysLayout.DEFAULT -> DEFAULT_KEY_IDS
    ExtraKeysLayout.EDIT -> listOf("ESC", "SHIFT", "INS", "DEL", "PgUp", "PgDn", "TAB", "⇧TAB", "HOME", "END", "⌫", "Enter")
    ExtraKeysLayout.FUNCTION -> (1..12).map { "F$it" }
    ExtraKeysLayout.CONTROL -> listOf("CTRL", "^A", "^E", "^U", "^K", "^W", "^Y", "^L", "^D", "^Z", "^R", "^C")
    ExtraKeysLayout.CUSTOM -> validCustomKeyIds(customIds)
}

fun layoutRows(layout: ExtraKeysLayout, customIds: List<String>): List<List<ExtraKey>> {
    val keys = layoutKeyIds(layout, customIds).map { id -> keysById.getValue(id) }
    return listOf(
        keys.take(6) + ExtraKey.Action(ACTION_PANEL),
        keys.drop(6) + ExtraKey.Action(ACTION_COMPOSER),
    )
}
