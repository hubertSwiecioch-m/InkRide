package com.speedevand.inkride.core.presentation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    width: Dp = 4.dp,
    color: Color = Color.Black
): Modifier = drawWithContent {
    drawContent()
    if (!scrollState.isScrollInProgress) return@drawWithContent

    val scrollValue = scrollState.value
    val maxValue = scrollState.maxValue
    val viewHeight = size.height
    val contentHeight = maxValue.toFloat() + viewHeight

    if (contentHeight > viewHeight) {
        val scrollbarHeight = (viewHeight / contentHeight) * viewHeight
        val scrollbarY = (scrollValue.toFloat() / contentHeight) * viewHeight

        drawRect(
            color = color,
            topLeft = Offset(size.width - width.toPx(), scrollbarY),
            size = Size(width.toPx(), scrollbarHeight)
        )
    }
}

fun Modifier.verticalScrollbar(
    state: LazyListState,
    width: Dp = 4.dp,
    color: Color = Color.Black
): Modifier = drawWithContent {
    drawContent()
    if (!state.isScrollInProgress) return@drawWithContent

    val layoutInfo = state.layoutInfo
    val totalItemsCount = layoutInfo.totalItemsCount
    val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
    val firstIndex = firstVisible?.index ?: 0
    val firstOffset = firstVisible?.offset ?: 0
    val firstSize = firstVisible?.size?.takeIf { it > 0 } ?: 1
    val visibleCount = layoutInfo.visibleItemsInfo.size

    if (totalItemsCount > 0 && visibleCount > 0) {
        val viewHeight = size.height
        val scrollbarHeight = (visibleCount.toFloat() / totalItemsCount) * viewHeight
        val scrollFraction = (firstIndex.toFloat() - firstOffset.toFloat() / firstSize) / totalItemsCount
        val scrollbarY = scrollFraction * viewHeight

        drawRect(
            color = color,
            topLeft = Offset(size.width - width.toPx(), scrollbarY),
            size = Size(width.toPx(), scrollbarHeight)
        )
    }
}
