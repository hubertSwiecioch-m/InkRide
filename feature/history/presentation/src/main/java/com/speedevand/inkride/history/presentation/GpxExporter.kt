package com.speedevand.inkride.history.presentation

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.speedevand.inkride.core.domain.Error
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.GpxBuilder
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class GpxExportError : Error {
    /** The ride exists but no GPS track points were recorded for it. */
    NO_TRACK,

    /** The ride couldn't be read, or the file couldn't be written. */
    FAILED
}

/**
 * Produces a shareable GPX file for a saved ride. Defined as an interface so the
 * ViewModel can be unit-tested without an Android [Context] or the FileProvider.
 */
interface GpxExporter {
    suspend fun export(rideId: Long): Result<Uri, GpxExportError>
}

/**
 * Reads a ride and its track from the database, renders GPX via [GpxBuilder],
 * writes it to app-scoped storage, and returns a shareable [FileProvider] [Uri].
 *
 * Lives in the presentation layer because it needs Android [Context] and the
 * FileProvider; the actual GPX serialization stays pure in [GpxBuilder].
 */
class AndroidGpxExporter(
    private val context: Context,
    private val historyRepository: RideHistoryRepository,
    private val trackPointRepository: RideTrackPointRepository
) : GpxExporter {

    override suspend fun export(rideId: Long): Result<Uri, GpxExportError> = withContext(Dispatchers.IO) {
        val ride = when (val result = historyRepository.getById(rideId)) {
            is Result.Error -> return@withContext Result.Error(GpxExportError.FAILED)
            is Result.Success -> result.data
        }
        val points = when (val result = trackPointRepository.getPoints(rideId)) {
            is Result.Error -> return@withContext Result.Error(GpxExportError.FAILED)
            is Result.Success -> result.data
        }
        if (points.isEmpty()) {
            return@withContext Result.Error(GpxExportError.NO_TRACK)
        }

        try {
            val gpx = GpxBuilder.build(ride, points)
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(baseDir, GPX_DIR).apply { mkdirs() }
            val file = File(dir, "inkride_${fileTimestamp(ride.startTimestamp)}.gpx")
            file.writeText(gpx)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Result.Success(uri)
        } catch (e: Exception) {
            Result.Error(GpxExportError.FAILED)
        }
    }

    private fun fileTimestamp(ms: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms))

    private companion object {
        const val GPX_DIR = "gpx"
    }
}
