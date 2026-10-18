package org.familycircle.android.sync

import kotlinx.coroutines.test.runTest
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.LocationPointDto
import org.familycircle.android.api.LocationUploadResult
import org.familycircle.android.api.Rejection
import org.familycircle.android.data.queue.LocationQueue
import org.familycircle.android.data.queue.PendingLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FakeQueue(points: List<PendingLocation>) : LocationQueue {
    val items = points.toMutableList()
    override suspend fun oldest(limit: Int) = items.sortedBy { it.recordedAtMs }.take(limit)
    override suspend fun delete(ids: Collection<String>) {
        items.removeAll { it.id in ids }
    }
    override suspend fun count() = items.size
}

class FakeApi(private val respond: (List<LocationPointDto>) -> LocationUploadResult) : LocationApi {
    val calls = mutableListOf<List<LocationPointDto>>()
    override suspend fun uploadLocations(points: List<LocationPointDto>): LocationUploadResult {
        calls += points
        return respond(points)
    }
}

fun point(i: Int) = PendingLocation(
    id = "00000000-0000-4000-8000-%012d".format(i),
    recordedAtMs = 1_000L + i,
    lat = 47.0, lon = 8.0, accuracyM = 10f,
    altitudeM = null, speedMps = null, bearingDeg = null, batteryPct = null, batteryCharging = null,
    motion = "unknown", createdAtMs = 0,
)

class UploaderTest {
    private val acceptAll = { pts: List<LocationPointDto> -> LocationUploadResult(pts.map { it.id }, emptyList()) }

    @Test
    fun `uploads in batches of at most 200, oldest first`() = runTest {
        val queue = FakeQueue((449 downTo 0).map(::point))
        val api = FakeApi(acceptAll)
        val result = Uploader(queue, api).uploadAll()
        assertEquals(listOf(200, 200, 50), api.calls.map { it.size })
        assertEquals(point(0).id, api.calls[0].first().id)
        assertEquals(450, result.uploaded)
        assertEquals(0, result.remaining)
        assertNull(result.error)
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun `deletes accepted and rejected ids`() = runTest {
        val queue = FakeQueue((0 until 3).map(::point))
        val api = FakeApi { pts ->
            LocationUploadResult(listOf(pts[0].id), listOf(Rejection(pts[1].id, "too_old"), Rejection(pts[2].id, "invalid")))
        }
        val result = Uploader(queue, api).uploadAll()
        assertEquals(1, result.uploaded)
        assertEquals(2, result.rejected)
        assertEquals(0, result.remaining)
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun `keeps the queue on network error`() = runTest {
        val queue = FakeQueue((0 until 5).map(::point))
        val api = FakeApi { throw ApiException.Network(IOException("offline")) }
        val result = Uploader(queue, api).uploadAll()
        assertEquals(UploadError.Network, result.error)
        assertTrue(result.error!!.retryable)
        assertEquals(5, result.remaining)
        assertEquals(5, queue.items.size)
    }

    @Test
    fun `keeps the queue on 5xx and reports transient`() = runTest {
        val queue = FakeQueue((0 until 2).map(::point))
        val api = FakeApi { throw ApiException.Server(503, "internal", null) }
        val result = Uploader(queue, api).uploadAll()
        assertEquals(UploadError.Transient(503, null), result.error)
        assertEquals(2, queue.items.size)
    }

    @Test
    fun `stops after a failed refresh (session expired) and keeps points`() = runTest {
        val queue = FakeQueue((0 until 250).map(::point))
        val api = FakeApi { throw ApiException.SessionExpired("token_reused") }
        val result = Uploader(queue, api).uploadAll()
        assertEquals(UploadError.Unauthorized, result.error)
        assertEquals(1, api.calls.size)
        assertEquals(250, result.remaining)
    }

    @Test
    fun `stops after the first failing batch but keeps progress`() = runTest {
        val queue = FakeQueue((0 until 450).map(::point))
        var n = 0
        val api = FakeApi { pts ->
            if (n++ == 1) throw ApiException.Network(IOException("lost"))
            LocationUploadResult(pts.map { it.id }, emptyList())
        }
        val result = Uploader(queue, api).uploadAll()
        assertEquals(200, result.uploaded)
        assertEquals(250, result.remaining)
        assertEquals(UploadError.Network, result.error)
    }

    @Test
    fun `re-sending after a lost response is idempotent`() = runTest {
        // First attempt: the server stored the points but the response was lost.
        val queue = FakeQueue((0 until 3).map(::point))
        val serverSeen = mutableSetOf<String>()
        var loseResponse = true
        val api = FakeApi { pts ->
            pts.forEach { serverSeen += it.id }
            if (loseResponse) {
                loseResponse = false
                throw ApiException.Network(IOException("connection reset"))
            }
            // Already known ids are reported as accepted by the server.
            LocationUploadResult(pts.map { it.id }, emptyList())
        }
        val uploader = Uploader(queue, api)
        assertEquals(UploadError.Network, uploader.uploadAll().error)
        assertEquals(3, queue.items.size)
        val second = uploader.uploadAll()
        assertNull(second.error)
        assertEquals(api.calls[0].map { it.id }, api.calls[1].map { it.id })
        assertEquals(3, serverSeen.size)
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun `stops when the server acknowledges nothing`() = runTest {
        val queue = FakeQueue((0 until 3).map(::point))
        val api = FakeApi { LocationUploadResult(emptyList(), emptyList()) }
        val result = Uploader(queue, api).uploadAll()
        assertEquals(1, api.calls.size)
        assertTrue(result.error is UploadError.Permanent)
        assertEquals(3, result.remaining)
    }

    @Test
    fun `ignores ids that are not part of the batch`() = runTest {
        val queue = FakeQueue(listOf(point(1)))
        val api = FakeApi { pts -> LocationUploadResult(pts.map { it.id } + "foreign", emptyList()) }
        val result = Uploader(queue, api).uploadAll()
        assertEquals(1, result.uploaded)
    }

    @Test
    fun `empty queue makes no request`() = runTest {
        val api = FakeApi(acceptAll)
        val result = Uploader(FakeQueue(emptyList()), api).uploadAll()
        assertTrue(api.calls.isEmpty())
        assertEquals(UploadResult(0, 0, 0, null), result)
    }
}
