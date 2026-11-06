package it.ferdiu.opencodewrapper.api

import it.ferdiu.opencodewrapper.data.ServerConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

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
    fun `network failure degrades to V1 and is cached`() = runTest {
        server.shutdown() // connect fails

        assertEquals(ApiVersionDetector.ServerApi.V1, ApiVersionDetector.detect(config()))
    }

    @Test
    fun `probe result is cached per base url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":"2.2.5","pid":1,"urls":[],"paths":{"tmp":"/t"}}"""))

        ApiVersionDetector.detect(config())
        ApiVersionDetector.detect(config()) // cache hit: no second request

        assertEquals(1, server.requestCount)
    }
}
