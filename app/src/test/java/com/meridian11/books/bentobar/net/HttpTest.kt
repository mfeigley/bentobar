package com.meridian11.books.bentobar.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The rules for going online at all: which hosts, when, and what may be said about a request. */
class HttpTest {
    private val fake = FakeHttp()
    private val logged = ArrayList<String>()
    private val forecast = Request(Host.OPEN_METEO, "/v1/forecast", listOf("latitude" to "47.37", "longitude" to "8.55"))

    @Before fun open() {
        Http.transport = fake
        Http.allowed = { true }
        Http.connected = { true }
        Http.onMain = { false }
        Http.log = { logged += it }
        Http.resetCounts()
    }

    @After fun close() {
        Http.transport = HttpTransport()
        Http.allowed = { false }
        Http.connected = { true }
        Http.onMain = { false }
        Http.log = {}
        Http.resetCounts()
    }

    @Test fun fourHostsAndNoOther() {
        assertEquals(setOf("api.open-meteo.com", "air-quality-api.open-meteo.com", "geocoding-api.open-meteo.com", "airlabs.co", "api.finnhub.io", "api.frankfurter.dev", "site.api.espn.com"), Host.entries.map { it.domain }.toSet())
    }

    @Test fun everyAddressIsHttpsToOneOfThem() {
        for (host in Host.entries) {
            val address = HttpTransport.address(Request(host, "/v1/search", listOf("name" to "São Paulo & co", "count" to "5")))!!
            assertTrue(address, address.startsWith("https://${host.domain}/v1/search?"))
            // The query is encoded: nothing in a city's name can start a new parameter or leave the query.
            assertEquals("name=S%C3%A3o+Paulo+%26+co&count=5", address.substringAfter('?'))
        }
        assertEquals("https://airlabs.co/api/v9/flight", HttpTransport.address(Request(Host.AIRLABS, "/api/v9/flight")))
    }

    @Test fun aPathThatCouldLeadElsewhereIsRefusedBeforeAnythingIsSent() {
        val never = HttpTransport(open = { throw AssertionError("a connection was opened for a bad path") })
        for (path in listOf("", "v1/forecast", "//evil.example/x", "/a//b", "/..", "/a/../b", "/a?x=1", "/a#b", "/@evil.example", "/a@b",
            "/a b", "/a\\b", "/a\nb", "/ä", "/a/", "/%2e%2e/x", "/a:b")) {
            assertNull(path, HttpTransport.address(Request(Host.AIRLABS, path)))
            val reply = never.get(Request(Host.AIRLABS, path))
            assertTrue(path, reply is Reply.Failed && reply.why == Why.UNREADABLE)
        }
    }

    @Test fun nothingGoesOutUnlessTheAppAllowsIt() {
        Http.allowed = { false }
        val reply = Http.get(forecast)
        assertTrue(reply is Reply.Failed && reply.why == Why.OFF)
        assertEquals(emptyList<Request>(), fake.asked)
        assertEquals(0, Http.sent(Host.OPEN_METEO))
    }

    @Test fun theGateIsAskedForTheRequestsOwnHost() {
        Http.allowed = { it == Host.OPEN_METEO }
        assertTrue(Http.get(forecast) is Reply.Failed) // the fake has no answer: a 404, but it was asked
        assertEquals(1, fake.asked.size)
        val flights = Http.get(Request(Host.AIRLABS, "/api/v9/flight"))
        assertTrue(flights is Reply.Failed && flights.why == Why.OFF)
        assertEquals(1, fake.asked.size)
    }

    @Test fun withoutANetworkNothingIsTried() {
        Http.connected = { false }
        val reply = Http.get(forecast)
        assertTrue(reply is Reply.Failed && reply.why == Why.OFFLINE)
        assertEquals(emptyList<Request>(), fake.asked)
        assertEquals(0, Http.sent(Host.OPEN_METEO))
    }

    @Test fun neverFromTheMainThread() {
        Http.onMain = { true }
        val reply = Http.get(forecast)
        assertTrue(reply is Reply.Failed && reply.why == Why.OFF)
        assertEquals(emptyList<Request>(), fake.asked)
    }

    @Test fun anAnswerComesBackAndRequestsAreCountedPerHost() {
        fake.reply(Host.OPEN_METEO, "/v1/forecast", """{"current":{}}""")
        val reply = Http.get(forecast)
        assertEquals("""{"current":{}}""", (reply as Reply.Ok).text)
        Http.get(forecast)
        Http.get(Request(Host.AIRLABS, "/api/v9/flight"))
        assertEquals(2, Http.sent(Host.OPEN_METEO))
        assertEquals(1, Http.sent(Host.AIRLABS))
        assertEquals(0, Http.sent(Host.OPEN_METEO_GEOCODING))
        Http.resetCounts()
        assertEquals(0, Http.sent(Host.OPEN_METEO))
    }

    @Test fun aRequestSaysItsHostAndPathAndNothingOfItsQuery() {
        val request = Request(Host.AIRLABS, "/api/v9/flight", listOf("flight_iata" to "LH455", "api_key" to "a-key-nobody-may-see"))
        assertEquals("airlabs.co/api/v9/flight", request.toString())
        fake.reply(Host.AIRLABS, "/api/v9/flight", """{"request":{"key":{"api_key":"a-key-nobody-may-see"}},"response":{}}""")
        Http.get(request)
        Http.get(Request(Host.OPEN_METEO_GEOCODING, "/v1/search", listOf("name" to "Springfield")))
        assertEquals(2, logged.size)
        for (line in logged) {
            assertFalse(line, line.contains("a-key-nobody-may-see"))
            assertFalse(line, line.contains("LH455"))
            assertFalse(line, line.contains("Springfield"))
            assertFalse(line, line.contains("?"))
        }
        assertEquals("GET airlabs.co/api/v9/flight -> ok", logged[0])
    }

    @Test fun aTransportThatThrowsIsAFailureAndItsMessageGoesNowhere() {
        Http.transport = Transport { throw IllegalStateException("https://airlabs.co/api/v9/flight?api_key=a-key-nobody-may-see") }
        val reply = Http.get(Request(Host.AIRLABS, "/api/v9/flight"))
        assertTrue(reply is Reply.Failed && reply.why == Why.UNREADABLE)
        assertFalse(logged.any { it.contains("a-key-nobody-may-see") })
    }

    @Test fun whatWentWrongIsOneOfAFewKindsWithoutTheExceptionsText() {
        assertEquals(Why.OFFLINE, HttpTransport.why(java.net.UnknownHostException("airlabs.co")))
        assertEquals(Why.OFFLINE, HttpTransport.why(java.net.ConnectException("refused")))
        assertEquals(Why.OFFLINE, HttpTransport.why(java.net.NoRouteToHostException()))
        assertEquals(Why.OFFLINE, HttpTransport.why(javax.net.ssl.SSLHandshakeException("not the service's certificate")))
        assertEquals(Why.TIMEOUT, HttpTransport.why(java.net.SocketTimeoutException()))
        assertEquals(Why.UNREADABLE, HttpTransport.why(java.io.IOException("x")))
        assertEquals(Why.UNREADABLE, HttpTransport.why(IllegalStateException("x")))
        assertEquals("STATUS 429", Reply.Failed(Why.STATUS, 429, 60).toString())
        assertEquals("OFFLINE", Reply.Failed(Why.OFFLINE).toString())
        assertEquals("ok", Reply.Ok("""{"secret":"text"}""").toString())
    }
}
