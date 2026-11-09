package com.meridian11.books.bentobar.net

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * The only hosts BentoBar may ask. A request names one of these; there is no way to name another,
 * and no call anywhere that takes an address. Adding a host means adding it here, where it shows.
 */
enum class Host(val domain: String) {
    /** Weather forecasts (Open-Meteo). */
    OPEN_METEO("api.open-meteo.com"),
    /** Air quality and UV forecasts (Open-Meteo). */
    OPEN_METEO_AIR("air-quality-api.open-meteo.com"),
    /** City search for the weather and air quality items (Open-Meteo). */
    OPEN_METEO_GEOCODING("geocoding-api.open-meteo.com"),
    /** Flight times (AirLabs), with the user's own key. */
    AIRLABS("airlabs.co"),
    /** Stock quotes and the stock search (Finnhub), with the user's own key. */
    FINNHUB("api.finnhub.io"),
    /** Reference exchange rates from the European Central Bank (Frankfurter). */
    FRANKFURTER("api.frankfurter.dev"),
    /** Sports scores and schedules (ESPN). */
    ESPN("site.api.espn.com"),
}

/**
 * One GET. [path] starts with "/" and holds no query; [query] is encoded by the transport. A query
 * can hold a key or a city, so [toString] says the host and the path and nothing more: that is all
 * a log line or an error may ever show of a request.
 */
class Request(val host: Host, val path: String, val query: List<Pair<String, String>> = emptyList()) {
    override fun toString(): String = host.domain + path
}

/** What a request came to. Never an exception: its message could hold the address, and with it a key. */
sealed interface Reply {
    /** The service answered with a 2xx status: its text (UTF-8, at most [Http.MAX_BYTES]). */
    class Ok(val text: String) : Reply {
        override fun toString() = "ok"
    }

    /** No answer worth reading. [status]: the HTTP status where there was one, else 0. [retryAfterSec]: what a 429 or 503 asked for. */
    class Failed(val why: Why, val status: Int = 0, val retryAfterSec: Long? = null) : Reply {
        override fun toString() = if (status != 0) "$why $status" else "$why"
    }
}

enum class Why {
    /** Not asked at all: the service is switched off, no item that uses it is outside Off, nothing shows items, or this is the main thread. */
    OFF,
    /** No network, no route, no such name, or a connection that isn't the service's own (a public network's sign-in page). */
    OFFLINE,
    /** The time to connect or to read ran out, or the whole answer took longer than [Http.TOTAL_MS]. */
    TIMEOUT,
    /** A status outside 2xx. A redirect is one: it is never followed. 429 means "slow down". */
    STATUS,
    /** More than [Http.MAX_BYTES]. */
    TOO_LARGE,
    /** Anything else: a broken stream, a request that wasn't well formed. Whether a reply's text makes sense is its reader's question. */
    UNREADABLE,
}

/** What carries a request: [HttpTransport] in the app, a fake in tests. It must not throw. */
fun interface Transport {
    fun get(request: Request): Reply
}

/**
 * The one way out of the device. Pure Kotlin (no Android), so its rules are unit-tested on the JVM;
 * the app wires [allowed], [connected], [onMain] and [log] once at start (`Env.init`).
 *
 * A request goes out only if [allowed] says so for its host: the service is switched on, an item
 * that uses it is outside Off, and something that shows items is on screen. Until the app has wired
 * it, nothing is allowed. HTTPS only, to the [Host]s; the user agent is [USER_AGENT]; no
 * cookies, no identifiers, no redirects. Nothing of a request but its host and path is ever logged,
 * and nothing at all of a reply.
 */
object Http {
    const val USER_AGENT = "BentoBar"
    const val CONNECT_MS = 4_000
    const val READ_MS = 6_000
    /** A whole request, from asking to the last byte: an answer that trickles in is given up, so no load hangs on one. */
    const val TOTAL_MS = 15_000
    const val MAX_BYTES = 1024 * 1024

    /** Who may be asked right now. Closed until the app wires it. */
    @Volatile var allowed: (Host) -> Boolean = { false }
    /** Whether a network is up: asked before trying, so that being offline costs no wait and no attempt. */
    @Volatile var connected: () -> Boolean = { true }
    /** Whether the caller is on the main thread, where no request may run. */
    @Volatile var onMain: () -> Boolean = { false }
    /** Gets one line per request: "GET host/path -> ok" or "-> STATUS 429". Never a query, never a reply's text. */
    @Volatile var log: (String) -> Unit = {}
    @Volatile var transport: Transport = HttpTransport()

    private val counts = AtomicIntegerArray(Host.entries.size)

    /**
     * Asks, and waits for the answer: call it from a background load ([com.meridian11.books.bentobar.items.Refresher],
     * [com.meridian11.books.bentobar.items.Ask]), never from an item's state or from a composable.
     */
    fun get(request: Request): Reply {
        if (onMain()) return refused(request, "the main thread")
        if (!allowed(request.host)) return Reply.Failed(Why.OFF)
        if (!connected()) return Reply.Failed(Why.OFFLINE)
        counts.incrementAndGet(request.host.ordinal)
        val reply = try {
            transport.get(request)
        } catch (t: Throwable) {
            // A transport must not throw; if one does, its message stays here (it may hold the address).
            Reply.Failed(Why.UNREADABLE)
        }
        log("GET $request -> $reply")
        return reply
    }

    private fun refused(request: Request, why: String): Reply {
        log("GET $request refused: $why")
        return Reply.Failed(Why.OFF)
    }

    /** How many requests really went out to [host] since the app started (or [resetCounts]): for the debug hook and for tests. */
    fun sent(host: Host): Int = counts.get(host.ordinal)

    fun resetCounts() { for (i in 0 until counts.length()) counts.set(i, 0) }
}
