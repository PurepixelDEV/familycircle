package org.familycircle.android.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.LocationPointDto
import org.familycircle.android.api.LocationUploadResult
import org.familycircle.android.data.queue.LocationQueue
import org.familycircle.android.data.queue.PendingLocation
import org.familycircle.android.util.Rfc3339

/** The single API call the uploader needs (implemented by `ApiClient`, faked in tests). */
interface LocationApi {
    suspend fun uploadLocations(points: List<LocationPointDto>): LocationUploadResult
}

/** Why an upload run stopped early. */
sealed interface UploadError {
    /** Offline / timeout: keep the queue and retry later. */
    data object Network : UploadError

    /** 5xx, 429 or similar: keep the queue and retry later. */
    data class Transient(val status: Int, val retryAfterSec: Long?) : UploadError

    /** The session ended (refresh failed) or is not authorised: the user must sign in. */
    data object Unauthorized : UploadError

    /** Not signed in / no server configured. */
    data object NotConfigured : UploadError

    /** Non-retryable server answer (e.g. 400/403) or a response that does not match the contract. */
    data class Permanent(val status: Int?, val code: String?) : UploadError

    val retryable: Boolean get() = this is Network || this is Transient
}

data class UploadResult(
    val uploaded: Int,
    val rejected: Int,
    val remaining: Int,
    val error: UploadError?,
) {
    val success: Boolean get() = error == null
}

/**
 * Drains the [LocationQueue] in batches of ≤ [batchSize] (oldest first).
 *
 * * IDs the server **accepted** *and* **rejected** are deleted locally – rejected points
 *   (`too_old`, `in_future`, `invalid`) will never be accepted, keeping them would block the queue.
 * * Network errors and transient server errors keep the queue untouched.
 * * Only one run at a time per process ([mutex]); the foreground service and the catch-up worker
 *   share one instance. Server-side idempotency (client UUIDs) makes overlapping runs harmless
 *   anyway, the mutex just avoids double traffic.
 */
class Uploader(
    private val queue: LocationQueue,
    private val api: LocationApi,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val maxBatchesPerRun: Int = 100,
) {
    init {
        require(batchSize in 1..500) { "the API accepts at most 500 points per batch" }
    }

    private val mutex = Mutex()

    suspend fun uploadAll(): UploadResult = mutex.withLock { drain() }

    private suspend fun drain(): UploadResult {
        var uploaded = 0
        var rejected = 0
        var error: UploadError? = null
        for (round in 0 until maxBatchesPerRun) {
            val batch = queue.oldest(batchSize)
            if (batch.isEmpty()) break
            val result = try {
                api.uploadLocations(batch.map { it.toDto() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                error = e.toUploadError()
                break
            }
            val batchIds = batch.mapTo(HashSet()) { it.id }
            val done = (result.accepted + result.rejected.map { it.id }).filter { it in batchIds }.toSet()
            if (done.isEmpty()) {
                // The server acknowledged nothing of this batch. Stop instead of looping forever.
                error = UploadError.Permanent(status = 200, code = "no_ids_acknowledged")
                break
            }
            queue.delete(done)
            uploaded += result.accepted.count { it in batchIds }
            rejected += result.rejected.count { it.id in batchIds }
        }
        return UploadResult(uploaded, rejected, queue.count(), error)
    }

    companion object {
        /** Stays well below the API limit of 500 so a batch remains small on slow mobile links. */
        const val DEFAULT_BATCH_SIZE = 200
    }
}

fun ApiException.toUploadError(): UploadError = when (this) {
    is ApiException.Network -> UploadError.Network
    is ApiException.SessionExpired, is ApiException.Unauthorized -> UploadError.Unauthorized
    is ApiException.NotLoggedIn, is ApiException.NoServer -> UploadError.NotConfigured
    is ApiException.Server ->
        if (isTransient) UploadError.Transient(status, retryAfterSec) else UploadError.Permanent(status, code)
    is ApiException.InvalidResponse -> UploadError.Permanent(null, "invalid_response")
}

/** Contract mapping; `recorded_at` is the fix's own time in RFC 3339 UTC with milliseconds. */
fun PendingLocation.toDto(): LocationPointDto = LocationPointDto(
    id = id,
    recordedAt = Rfc3339.format(recordedAtMs),
    lat = lat,
    lon = lon,
    accuracyM = accuracyM.toCleanDouble(),
    altitudeM = altitudeM,
    speedMps = speedMps?.toCleanDouble(),
    bearingDeg = bearingDeg?.toCleanDouble(),
    batteryPct = batteryPct,
    batteryCharging = batteryCharging,
    motion = motion,
)

/** `12.3f.toDouble()` is `12.300000190734863`; going through the shortest decimal string keeps `12.3`. */
internal fun Float.toCleanDouble(): Double = toString().toDouble()
