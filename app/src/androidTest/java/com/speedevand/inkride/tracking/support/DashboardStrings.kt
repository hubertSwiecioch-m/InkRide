package com.speedevand.inkride.tracking.support

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Resolves a Dashboard string resource through the real app context, so
 * status/goal/lap assertions compare against the actual localized string
 * the UI renders instead of a hardcoded English literal that would silently
 * drift from `strings.xml`.
 */
fun dashboardString(@StringRes resId: Int): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)
