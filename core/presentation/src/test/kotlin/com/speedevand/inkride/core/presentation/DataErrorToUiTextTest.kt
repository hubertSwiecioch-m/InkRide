package com.speedevand.inkride.core.presentation

import assertk.assertThat
import assertk.assertions.isInstanceOf
import com.speedevand.inkride.core.domain.DataError
import org.junit.jupiter.api.Test

class DataErrorToUiTextTest {
    @Test
    fun `NO_INTERNET maps to correct string resource`() {
        val result = DataError.Network.NO_INTERNET.toUiText()
        assertThat(result).isInstanceOf<UiText.StringResource>()
    }

    @Test
    fun `SERVER_ERROR maps to correct string resource`() {
        val result = DataError.Network.SERVER_ERROR.toUiText()
        assertThat(result).isInstanceOf<UiText.StringResource>()
    }

    @Test
    fun `DISK_FULL maps to correct string resource`() {
        val result = DataError.Local.DISK_FULL.toUiText()
        assertThat(result).isInstanceOf<UiText.StringResource>()
    }

    @Test
    fun `unknown error maps to generic string resource`() {
        val result = DataError.Network.UNKNOWN.toUiText()
        assertThat(result).isInstanceOf<UiText.StringResource>()
    }
}
