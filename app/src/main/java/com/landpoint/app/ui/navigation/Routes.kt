package com.landpoint.app.ui.navigation

object Routes {
    const val LIST = "lands"
    const val MAP = "map"
    const val SETTINGS = "settings"
    const val OFFLINE = "offline_maps"

    const val DETAIL = "land/{landId}"
    const val EDIT = "land/{landId}/edit"
    // Deliberately not "land/new" — that would also match the DETAIL pattern.
    const val NEW = "land_new"
    const val COMPASS = "land/{landId}/compass"
    const val SHAPE = "land/{landId}/shape"

    fun detail(landId: String) = "land/$landId"
    fun edit(landId: String) = "land/$landId/edit"
    fun compass(landId: String) = "land/$landId/compass"
    fun shape(landId: String) = "land/$landId/shape"

    const val ARG_LAND_ID = "landId"
}
