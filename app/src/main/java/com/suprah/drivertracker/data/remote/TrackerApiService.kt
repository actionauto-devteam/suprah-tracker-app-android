package com.suprah.drivertracker.data.remote

import com.suprah.drivertracker.data.remote.dto.Envelope
import com.suprah.drivertracker.data.remote.dto.PairData
import com.suprah.drivertracker.data.remote.dto.PairRequest
import com.suprah.drivertracker.data.remote.dto.PositionsRequest
import com.suprah.drivertracker.data.remote.dto.StatusData
import com.suprah.drivertracker.data.remote.dto.UploadData
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Invocation
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * The tracking-app endpoints. Full URLs are passed in because the server address can change at
 * runtime in dev builds.
 */
interface TrackerApiService {
    @POST
    suspend fun pair(@Url url: String, @Body body: PairRequest): Envelope<PairData>

    @GET
    suspend fun status(@Url url: String, @Header(AUTHORIZATION) authorization: String): Envelope<StatusData>

    @SlowCall
    @POST
    suspend fun sendPositions(
        @Url url: String,
        @Header(AUTHORIZATION) authorization: String,
        @Body body: PositionsRequest,
    ): Envelope<UploadData>

    @DELETE
    suspend fun unlink(@Url url: String, @Header(AUTHORIZATION) authorization: String)

    companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

/** A call that may take Suprah a while to answer (saving a batch of readings, spec T-11). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SlowCall

/** Gives [SlowCall] requests longer to answer than the client's normal timeouts. */
class SlowCallTimeoutInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val slow = request.tag(Invocation::class.java)?.method()?.isAnnotationPresent(SlowCall::class.java) == true
        val next =
            if (slow) {
                chain.withReadTimeout(SLOW_SECONDS, TimeUnit.SECONDS).withWriteTimeout(SLOW_SECONDS, TimeUnit.SECONDS)
            } else {
                chain
            }
        return next.proceed(request)
    }

    companion object {
        const val SLOW_SECONDS = 60
    }
}
