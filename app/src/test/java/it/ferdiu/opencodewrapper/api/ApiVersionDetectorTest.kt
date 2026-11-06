package it.ferdiu.opencodewrapper.api

import it.ferdiu.opencodewrapper.data.ServerConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ApiVersionDetectorTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        ApiVersionDetector.invalidate()
    }

    @After fun tearDown() = server.shutdown()

    private fun config(v1CompatMode: Boolean = true) = ServerConfig(
        baseUrl = server.url("/").toString().trimEnd('/'),
        authHeaderValue = null,
        v1CompatMode = v1CompatMode,
    )

    @Test
    fun `api info 200 with version detects V2 and factory yields the v2 client`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":"2.2.5","pid":123,"urls":[],"paths":{"tmp":"/tmp"}}"""))

        assertEquals(ApiVersionDetector.ServerApi.V2, ApiVersionDetector.detect(config()))
        assertTrue(ApiVersionDetector.clientFor(config()) is OpenCodeClientV2)
        assertEquals("/api/info", server.takeRequest().path)
    }

    @Test
    fun `api info 404 with v1 compat falls back to the v1 client`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(ApiVersionDetector.ServerApi.V1, ApiVersionDetector.detect(config()))
        assertTrue(ApiVersionDetector.clientFor(config()) is OpenCodeClientV1)
    }

    @Test
    fun `api info 404 without v1 compat throws`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            ApiVersionDetector.clientFor(config(v1CompatMode = false))
            fail("expected ApiVersionException")
        } catch (e: ApiVersionException) {
            assertTrue(e.message!!.contains("does not answer the OpenCode 2.x API"))
        }
    }

    @Test
    fun `spa catch-all 200 without version field is not v2`() = runTest {
        // A v1 server (or reverse proxy) may answer 200 with HTML for any
        // path; only a parseable JSON version field counts as v2.
        server.enqueue(MockResponse().setResponseCode(200).setBody("<!doctype html><html></html>"))

        assertEquals(ApiVersionDetector.ServerApi.V1, ApiVersionDetector.detect(config()))
    }

    @Test
    fun `network failure propagates and is not cached`() = runTest {
        // Transport failures (connection reset before any HTTP answer) must
        // not degrade to a cached V1: the caller's reconnect/backoff retries
        // the probe later instead of serving a poisoned result.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":"2.2.5","pid":1,"urls":[],"paths":{"tmp":"/t"}}"""))

        try {
            ApiVersionDetector.detect(config())
            fail("expected IOException")
        } catch (e: IOException) {
            // expected: transport failure propagates
        }

        // Second probe hits the wire again (no cache entry) and succeeds.
        assertEquals(ApiVersionDetector.ServerApi.V2, ApiVersionDetector.detect(config()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `http 404 answer is cached per base url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(ApiVersionDetector.ServerApi.V1, ApiVersionDetector.detect(config()))
        assertEquals(ApiVersionDetector.ServerApi.V1, ApiVersionDetector.detect(config())) // cache hit

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cached definitive V1 for a strict profile throws without re-probing`() = runTest {
        // No-flood guarantee: within the TTL a cached V1 answer for a
        // strict-v2 profile throws immediately without another request.
        server.enqueue(MockResponse().setResponseCode(404))

        val strict = config(v1CompatMode = false)
        try {
            ApiVersionDetector.clientFor(strict)
            fail("expected ApiVersionException")
        } catch (e: ApiVersionException) {
            assertTrue(e.message!!.contains("does not answer the OpenCode 2.x API"))
        }

        try {
            ApiVersionDetector.clientFor(strict) // cached: still throws
            fail("expected ApiVersionException")
        } catch (e: ApiVersionException) {
            // expected
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cached result expires after the TTL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":"2.2.5","pid":1,"urls":[],"paths":{"tmp":"/t"}}"""))
        server.enqueue(MockResponse().setResponseCode(404))

        val savedTtl = ApiVersionDetector.cacheTtlMillis
        try {
            ApiVersionDetector.cacheTtlMillis = 0 // every entry is immediately stale

            assertEquals(ApiVersionDetector.ServerApi.V2, ApiVersionDetector.detect(config()))
            assertEquals(ApiVersionDetector.ServerApi.V1, ApiVersionDetector.detect(config())) // re-probed

            assertEquals(2, server.requestCount)
        } finally {
            ApiVersionDetector.cacheTtlMillis = savedTtl
        }
    }

    @Test
    fun `probe result is cached per base url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":"2.2.5","pid":1,"urls":[],"paths":{"tmp":"/t"}}"""))

        ApiVersionDetector.detect(config())
        ApiVersionDetector.detect(config()) // cache hit: no second request

        assertEquals(1, server.requestCount)
    }
}
