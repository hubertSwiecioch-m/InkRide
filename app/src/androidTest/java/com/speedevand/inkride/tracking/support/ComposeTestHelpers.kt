package com.speedevand.inkride.tracking.support

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag

/**
 * The literal text rendered by a single tagged text node (e.g. a `TextMMD`
 * carrying a `Modifier.testTag(...)`). Reading semantics directly, rather
 * than matching on formatted/localized strings, keeps assertions robust to
 * decimal-separator/unit differences.
 */
fun SemanticsNodeInteraction.text(): String =
    fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString(separator = "") { it.text }

fun ComposeTestRule.textOf(tag: String): String = onNodeWithTag(tag).assertIsDisplayed().text()

/**
 * Polls a tagged node's text against [predicate] on a real wall clock — ride
 * metrics update from a background coroutine (the fake sensor sources feed
 * `RideTracker` on `Dispatchers.Default`), not Compose's test clock, so this
 * relies on [ComposeTestRule.waitUntil]'s real-time polling.
 */
fun ComposeTestRule.waitUntilTagText(
    tag: String,
    timeoutMillis: Long = 15_000L,
    predicate: (String) -> Boolean,
) {
    waitUntil(timeoutMillis) {
        runCatching { predicate(textOf(tag)) }.getOrDefault(false)
    }
}
