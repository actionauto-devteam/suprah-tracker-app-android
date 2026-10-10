package com.suprah.drivertracker.data.remote

import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.domain.DeviceCredentials
import com.suprah.drivertracker.domain.Position
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Spec section 3: the server contract, as the app sends and reads it. */
class RetrofitTrackerApiTest {
    private lateinit var server: MockWebServer
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    private val credentials = DeviceCredentials("dev-123", "secret-key")

    private fun api(readTimeoutSeconds: Long = 20): RetrofitTrackerApi {
        val client =
            OkHttpClient.Builder()
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .addInterceptor(SlowCallTimeoutInterceptor())
                .build()
        val service =
            Retrofit.Builder()
                .baseUrl("https://localhost/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TrackerApiService::class.java)
        return RetrofitTrackerApi(service, json)
    }

    private val baseUrl get() = server.url("/").toString().trimEnd('/')

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(data: String) = MockResponse().setResponseCode(200).setBody("""{"success":true,"data":$data}""")

    @Test
    fun `pair sends the code and device details and reads the new credentials (P-5, P-6)`() = runBlocking<Unit> {
        server.enqueue(
            ok("""{"deviceId":"dev-9","deviceKey":"k-9","status":"waiting_for_approval","driverName":"Jo","shouldTrack":false}""")
                .setResponseCode(201),
        )

        val result = api().pair(baseUrl, "ABCD2345", "Xiaomi 22111317G", "1.0.0")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/tracking-app/pair")
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertThat(body["code"]!!.jsonPrimitive.content).isEqualTo("ABCD2345")
        assertThat(body["platform"]!!.jsonPrimitive.content).isEqualTo("android")
        assertThat(body["deviceName"]!!.jsonPrimitive.content).isEqualTo("Xiaomi 22111317G")
        assertThat(request.getHeader("Authorization")).isNull()

        val pairing = (result as ApiResult.Ok).value
        assertThat(pairing.credentials).isEqualTo(DeviceCredentials("dev-9", "k-9"))
        assertThat(pairing.status.approval).isEqualTo(Approval.WAITING)
        assertThat(pairing.status.driverName).isEqualTo("Jo")
    }

    @Test
    fun `status signs with the device key and fills in defaults (section 3)`() = runBlocking<Unit> {
        server.enqueue(ok("""{"status":"approved","shouldTrack":true,"activeLoadCount":2,"uploadIntervalSeconds":9999}"""))

        val status = (api().status(baseUrl, credentials) as ApiResult.Ok).value

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/tracking-app/status")
        assertThat(request.getHeader("Authorization")).isEqualTo("Device dev-123:secret-key")
        assertThat(status.approval).isEqualTo(Approval.APPROVED)
        assertThat(status.shouldTrack).isTrue()
        assertThat(status.activeLoadCount).isEqualTo(2)
        assertThat(status.driverName).isEqualTo("Driver")
        assertThat(status.uploadIntervalSeconds).isEqualTo(300)
        assertThat(status.locationIntervalSeconds).isEqualTo(15)
    }

    @Test
    fun `positions go as ISO times and leave out what the phone didn't measure (T-7)`() = runBlocking<Unit> {
        server.enqueue(ok("""{"received":2,"accepted":2,"status":"approved","shouldTrack":true}"""))
        val positions =
            listOf(
                Position(40.1, -111.9, measuredAtMs = 1_791_213_125_669, accuracy = 4.5f, speed = 20f, heading = 90f),
                Position(40.2, -111.8, measuredAtMs = 1_791_213_140_000),
            )

        val upload = (api().sendPositions(baseUrl, credentials, positions) as ApiResult.Ok).value

        val sent = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["positions"]!!.jsonArray
        assertThat(sent).hasSize(2)
        val first = sent[0].jsonObject
        assertThat(first["measuredAt"]!!.jsonPrimitive.content).isEqualTo("2026-10-05T15:12:05.669Z")
        assertThat(first["speed"]!!.jsonPrimitive.content).isEqualTo("20.0")
        assertThat(sent[1].jsonObject.keys).containsExactly("lat", "lng", "measuredAt")
        assertThat(upload.accepted).isEqualTo(2)
    }

    @Test
    fun `refusals keep the reason and the message for people (section 3)`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"success":false,"code":401,"reason":"device_unlinked","message":"This phone was unlinked."}"""),
        )
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))

        val unlinked = api().status(baseUrl, credentials)
        val gateway = api().status(baseUrl, credentials)

        assertThat(unlinked).isEqualTo(ApiResult.Refused(401, "device_unlinked", "This phone was unlinked."))
        assertThat(gateway).isEqualTo(ApiResult.Refused(502, null, null))
    }

    @Test
    fun `no server means offline, not a refusal (T-14)`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = api().status("http://127.0.0.1:$closedPort", credentials)

        assertThat(result).isEqualTo(ApiResult.Offline(timedOut = false))
    }

    @Test
    fun `an unreadable success answer counts as no answer`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        assertThat(api().status(baseUrl, credentials)).isEqualTo(ApiResult.Offline(timedOut = false))
    }

    @Test
    fun `a slow answer is a timeout for status but uploads get longer (T-11)`() = runBlocking<Unit> {
        server.enqueue(ok("""{"status":"approved"}""").setHeadersDelay(2, TimeUnit.SECONDS))
        server.enqueue(ok("""{"received":1,"accepted":1}""").setHeadersDelay(2, TimeUnit.SECONDS))
        val shortTimeouts = api(readTimeoutSeconds = 1)

        val status = shortTimeouts.status(baseUrl, credentials)
        val upload = shortTimeouts.sendPositions(baseUrl, credentials, listOf(Position(1.0, 2.0, 0L)))

        assertThat(status).isEqualTo(ApiResult.Offline(timedOut = true))
        assertThat(upload).isInstanceOf(ApiResult.Ok::class.java)
    }

    @Test
    fun `unlink calls DELETE on the device`() = runBlocking<Unit> {
        server.enqueue(ok("""{"unlinked":true}"""))

        val result = api().unlink(baseUrl, credentials)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.path).isEqualTo("/api/tracking-app/device")
        assertThat(result).isEqualTo(ApiResult.Ok(Unit))
    }

    @Test
    fun `credentials never print the key`() {
        assertThat(credentials.toString()).doesNotContain("secret-key")
    }
}
