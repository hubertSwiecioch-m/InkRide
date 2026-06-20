package com.speedevand.inkride.dashboard.presentation

import android.content.Context
import android.net.Uri
import com.speedevand.inkride.core.domain.Error
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.GpxParseError
import com.speedevand.inkride.core.domain.tracking.GpxRouteParser
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class GpxLoadError : Error {
    /** The selected file couldn't be opened or read. */
    READ_FAILED,

    /** The file held no usable track or route points. */
    EMPTY,

    /** The file was not well-formed GPX. */
    MALFORMED
}

/**
 * Reads a GPX file chosen by the rider into a [PlannedRoute]. An interface so the
 * ViewModel can be unit-tested without an Android [Context]; the actual parsing
 * stays pure in [GpxRouteParser].
 */
interface GpxRouteLoader {
    suspend fun load(uri: Uri): Result<PlannedRoute, GpxLoadError>
}

/** Reads the picked document via [Context]'s content resolver, then parses it. */
class AndroidGpxRouteLoader(
    private val context: Context
) : GpxRouteLoader {

    override suspend fun load(uri: Uri): Result<PlannedRoute, GpxLoadError> =
        withContext(Dispatchers.IO) {
            val xml = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: return@withContext Result.Error(GpxLoadError.READ_FAILED)
            } catch (e: Exception) {
                return@withContext Result.Error(GpxLoadError.READ_FAILED)
            }

            when (val parsed = GpxRouteParser.parse(xml)) {
                is Result.Success -> Result.Success(parsed.data)
                is Result.Error -> Result.Error(
                    when (parsed.error) {
                        GpxParseError.EMPTY -> GpxLoadError.EMPTY
                        GpxParseError.MALFORMED -> GpxLoadError.MALFORMED
                    }
                )
            }
        }
}
