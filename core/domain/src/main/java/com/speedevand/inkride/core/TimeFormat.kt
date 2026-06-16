package com.speedevand.inkride.core

import com.speedevand.inkride.core.CoreConstants.FORMAT_CLOCK
import com.speedevand.inkride.core.CoreConstants.SECONDS_IN_HOUR
import com.speedevand.inkride.core.CoreConstants.SECONDS_IN_MINUTE
import java.util.Locale

/** Formats a duration in seconds as `HH:MM:SS`. Shared by every metric/UI mapper. */
fun Long.toClockString(): String {
    val hours = this / SECONDS_IN_HOUR
    val minutes = (this % SECONDS_IN_HOUR) / SECONDS_IN_MINUTE
    val seconds = this % SECONDS_IN_MINUTE
    return String.format(Locale.US, FORMAT_CLOCK, hours, minutes, seconds)
}
