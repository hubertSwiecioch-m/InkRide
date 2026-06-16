package com.speedevand.inkride.core.design_system

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.mudita.mmd.ThemeMMD

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InkRideTheme(content: @Composable () -> Unit) {
    ThemeMMD {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            content()
        }
    }
}
