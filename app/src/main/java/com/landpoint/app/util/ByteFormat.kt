package com.landpoint.app.util

import java.util.Locale

/**
 * A file size, in the units a file manager shows.
 *
 * Shared rather than per-screen because three places now report a size — imported
 * archives in Settings, a download estimate before it starts, and the progress of one
 * running — and two of them are read against each other. A screen that says "1.2 GB"
 * where another says "1229 MB" reads as two different numbers.
 *
 * [Locale.US] on purpose. This is a size, not a measurement of anything the user
 * recorded, so the app's own decimal-comma rules do not apply to it; binary units
 * because that is what the phone's own storage screen counts in.
 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 ->
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
