package com.landpoint.app.ui.lands

import com.landpoint.app.data.model.Land
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One row of the list: a heading, or a land.
 *
 * Flat rather than a list of groups, because `LazyColumn` composes one row at a
 * time — a nested list would pull every card of a group into composition the
 * moment its heading came into view, which for the longest group is most of the
 * list.
 */
internal sealed interface LandRow {

    /** A run of lands the order in view puts together, and how many there are. */
    data class Header(val label: String, val count: Int) : LandRow

    data class Item(val land: Land) : LandRow
}

/**
 * Splits an already-sorted list into headed runs.
 *
 * The headings follow the order in view rather than being chosen separately: a
 * month heading over a list sorted by name would cut it in places that mean
 * nothing. Two kinds of run come back without a heading — orders that do not
 * group at all, and runs there is honestly nothing to call, such as parcel
 * numbers that are bare digits — so the list stays as it was rather than carrying
 * a made-up label.
 */
internal fun groupedRows(
    lands: List<Land>,
    order: SortOrder,
    locale: Locale,
    noParcelLabel: String
): List<LandRow> {
    if (!order.hasGroups) return lands.map { LandRow.Item(it) }
    // Built once: a formatter per row would be rebuilt on every re-sort.
    val months = SimpleDateFormat("MMMM yyyy", locale)
    val runs = mutableListOf<Pair<String?, MutableList<Land>>>()
    for (land in lands) {
        val label = order.groupLabel(land, months, locale, noParcelLabel)
        val open = runs.lastOrNull()
        if (open != null && open.first == label) open.second.add(land)
        else runs.add(label to mutableListOf(land))
    }
    return runs.flatMap { (label, group) ->
        val items = group.map { LandRow.Item(it) }
        if (label == null) items else listOf(LandRow.Header(label, group.size)) + items
    }
}

/** Orders whose runs are something a heading can name. */
private val SortOrder.hasGroups: Boolean
    get() = when (this) {
        SortOrder.DATE_DESC, SortOrder.DATE_ASC,
        SortOrder.NAME_ASC, SortOrder.NAME_DESC,
        SortOrder.PARCEL_ASC -> true
        // Distance and area are continuous. Any heading over a run of them would
        // draw a line where the ground has none.
        SortOrder.DISTANCE, SortOrder.AREA_DESC, SortOrder.AREA_ASC -> false
    }

private fun SortOrder.groupLabel(
    land: Land,
    months: DateFormat,
    locale: Locale,
    noParcelLabel: String
): String? = when (this) {
    SortOrder.DATE_DESC, SortOrder.DATE_ASC -> months.format(Date(land.createdAt))
    SortOrder.NAME_ASC, SortOrder.NAME_DESC -> land.name.initialGroup(locale)
    SortOrder.PARCEL_ASC -> {
        val parcel = land.parcelNumber?.trim()?.ifBlank { null }
        // A land with no parcel number gets a heading of its own, because that is
        // a fact about it worth seeing; one whose number is bare digits gets none,
        // because the digits name nothing the next land could share.
        if (parcel == null) noParcelLabel else parcel.parcelGroup()
    }
    SortOrder.DISTANCE, SortOrder.AREA_DESC, SortOrder.AREA_ASC -> null
}

/** First letter, uppercased; anything else groups under one symbol heading. */
private fun String.initialGroup(locale: Locale): String? {
    val first = trim().firstOrNull() ?: return null
    return if (first.isLetter()) first.toString().uppercase(locale) else "#"
}

/**
 * The written part in front of the numbers — `SHM` out of `SHM 1234/2019`, `Blok
 * A` out of `Blok A/12`. Null when the number starts with a digit: there is no
 * prefix to head the run with.
 */
private fun String.parcelGroup(): String? = takeWhile { it !in '0'..'9' }
    .trim()
    .trimEnd('/', '\\', '-', '.', ',', ':', ';', '_')
    .trim()
    .ifBlank { null }
