package com.speedevand.inkride.dashboard.presentation

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

class AndroidGpxRouteLoaderTest {
    private val context = mock<Context>()
    private val contentResolver = mock<ContentResolver>()
    private val uri = mock<Uri>()

    private val loader: GpxRouteLoader by lazy {
        whenever(context.contentResolver).thenReturn(contentResolver)
        AndroidGpxRouteLoader(context)
    }

    @Test
    fun `load returns READ_FAILED when the stream cannot be opened`() =
        runTest {
            whenever(contentResolver.openInputStream(uri)).thenReturn(null)

            val result = loader.load(uri)

            assertThat(result).isEqualTo(Result.Error(GpxLoadError.READ_FAILED))
        }

    @Test
    fun `load returns READ_FAILED when opening the stream throws`() =
        runTest {
            whenever(contentResolver.openInputStream(uri)).thenThrow(FileNotFoundException("boom"))

            val result = loader.load(uri)

            assertThat(result).isEqualTo(Result.Error(GpxLoadError.READ_FAILED))
        }

    @Test
    fun `load returns the parsed route on valid GPX`() =
        runTest {
            val gpx =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1">
                  <trk>
                    <name>Morning Loop</name>
                    <trkseg>
                      <trkpt lat="52.0" lon="21.0"/>
                    </trkseg>
                  </trk>
                </gpx>
                """.trimIndent()
            whenever(contentResolver.openInputStream(uri))
                .thenReturn(ByteArrayInputStream(gpx.toByteArray(Charsets.UTF_8)))

            val result = loader.load(uri)

            assertThat(result).isInstanceOf<Result.Success<PlannedRoute>>()
            assertThat((result as Result.Success).data.name).isEqualTo("Morning Loop")
        }

    @Test
    fun `load maps an EMPTY parse error to GpxLoadError_EMPTY`() =
        runTest {
            val gpx = """<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1"></gpx>"""
            whenever(contentResolver.openInputStream(uri))
                .thenReturn(ByteArrayInputStream(gpx.toByteArray(Charsets.UTF_8)))

            val result = loader.load(uri)

            assertThat(result).isEqualTo(Result.Error(GpxLoadError.EMPTY))
        }

    @Test
    fun `load maps a MALFORMED parse error to GpxLoadError_MALFORMED`() =
        runTest {
            val notXml = "this is not xml at all <<<"
            whenever(contentResolver.openInputStream(uri))
                .thenReturn(ByteArrayInputStream(notXml.toByteArray(Charsets.UTF_8)))

            val result = loader.load(uri)

            assertThat(result).isEqualTo(Result.Error(GpxLoadError.MALFORMED))
        }
}
