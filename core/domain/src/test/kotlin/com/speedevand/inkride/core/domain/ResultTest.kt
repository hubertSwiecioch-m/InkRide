package com.speedevand.inkride.core.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class ResultTest {

    // ── map ────────────────────────────────────────────────────────────────

    @Test
    fun `map transforms success data`() {
        val result: Result<Int, DataError.Local> = Result.Success(42)
        val mapped = result.map { it.toString() }
        assertThat(mapped).isInstanceOf<Result.Success<String>>()
        assertThat((mapped as Result.Success).data).isEqualTo("42")
    }

    @Test
    fun `map passes through error unchanged`() {
        val error = DataError.Local.UNKNOWN
        val result: Result<Int, DataError.Local> = Result.Error(error)
        val mapped = result.map { it.toString() }
        assertThat(mapped).isInstanceOf<Result.Error<DataError.Local>>()
        assertThat((mapped as Result.Error).error).isEqualTo(error)
    }

    // ── onSuccess ──────────────────────────────────────────────────────────

    @Test
    fun `onSuccess executes action on success`() {
        var called = false
        val result: Result<String, DataError.Local> = Result.Success("hello")
        result.onSuccess { called = true }
        assertThat(called).isEqualTo(true)
    }

    @Test
    fun `onSuccess receives correct data`() {
        var received: String? = null
        val result: Result<String, DataError.Local> = Result.Success("hello")
        result.onSuccess { received = it }
        assertThat(received).isEqualTo("hello")
    }

    @Test
    fun `onSuccess does not execute on error`() {
        var called = false
        val result: Result<String, DataError.Local> = Result.Error(DataError.Local.UNKNOWN)
        result.onSuccess { called = true }
        assertThat(called).isEqualTo(false)
    }

    @Test
    fun `onSuccess returns original result on success`() {
        val result: Result<String, DataError.Local> = Result.Success("hello")
        val returned = result.onSuccess { /* no-op */ }
        assertThat(returned).isInstanceOf<Result.Success<String>>()
        assertThat((returned as Result.Success).data).isEqualTo("hello")
    }

    // ── onFailure ──────────────────────────────────────────────────────────

    @Test
    fun `onFailure executes action on error`() {
        var called = false
        val error = DataError.Local.DISK_FULL
        val result: Result<String, DataError.Local> = Result.Error(error)
        result.onFailure { called = true }
        assertThat(called).isEqualTo(true)
    }

    @Test
    fun `onFailure receives correct error`() {
        var received: DataError.Local? = null
        val error = DataError.Local.DISK_FULL
        val result: Result<String, DataError.Local> = Result.Error(error)
        result.onFailure { received = it }
        assertThat(received).isEqualTo(error)
    }

    @Test
    fun `onFailure does not execute on success`() {
        var called = false
        val result: Result<String, DataError.Local> = Result.Success("hello")
        result.onFailure { called = true }
        assertThat(called).isEqualTo(false)
    }

    @Test
    fun `onFailure returns original result on error`() {
        val error = DataError.Local.UNKNOWN
        val result: Result<String, DataError.Local> = Result.Error(error)
        val returned = result.onFailure { /* no-op */ }
        assertThat(returned).isInstanceOf<Result.Error<DataError.Local>>()
        assertThat((returned as Result.Error).error).isEqualTo(error)
    }

    // ── asEmptyResult ──────────────────────────────────────────────────────

    @Test
    fun `asEmptyResult converts success to unit result`() {
        val result: Result<String, DataError.Local> = Result.Success("hello")
        val empty = result.asEmptyResult()
        assertThat(empty).isInstanceOf<Result.Success<Unit>>()
    }

    @Test
    fun `asEmptyResult passes through error`() {
        val error = DataError.Local.NOT_FOUND
        val result: Result<String, DataError.Local> = Result.Error(error)
        val empty = result.asEmptyResult()
        assertThat(empty).isInstanceOf<Result.Error<DataError.Local>>()
        assertThat((empty as Result.Error).error).isEqualTo(error)
    }
}
