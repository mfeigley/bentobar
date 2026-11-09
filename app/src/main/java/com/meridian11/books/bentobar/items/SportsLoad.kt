package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.items.SportsRules.League
import com.meridian11.books.bentobar.items.SportsRules.Scoreboard
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import com.meridian11.books.bentobar.net.Why

/**
 * Fetches scoreboard snapshots from ESPN (`site.api.espn.com`) and persists the last good parsed
 * [Scoreboard] per league so restarts do not re-request fresh data.
 * Pure Kotlin, no Android imports, and no logging.
 */
object SportsLoad {
    private val service = Online.Service.ESPN

    /** The leagues currently followed by active Sports items in [items]. */
    fun leagues(items: List<ItemConfig>): Set<League> =
        items.filter { it.type == "sports" && it.section != Section.OFF }
            .flatMapTo(HashSet()) { SportsRules.leagues(it) }

    /** All team filter tokens across active Sports items in [items]; empty if any item follows a whole league unfiltered. */
    fun followedTeamsFor(league: League, items: List<ItemConfig>): List<String> {
        val watching = items.filter { it.type == "sports" && it.section != Section.OFF && league in SportsRules.leagues(it) }
        if (watching.isEmpty()) return emptyList()
        // If any active item watches this league with no team filter, all games in the league are relevant.
        if (watching.any { SportsRules.teams(it).isEmpty() }) return emptyList()
        return watching.flatMap { SportsRules.teams(it) }.distinct()
    }

    fun load(league: League, last: Scoreboard?, now: Long, activeLeagues: () -> Set<League> = { setOf(league) }): Scoreboard? {
        if (!Online.on(service)) return null
        val before = Http.sent(Host.ESPN)
        val reply = Http.get(Request(Host.ESPN, league.path))
        val went = Http.sent(Host.ESPN) != before
        return when (reply) {
            is Reply.Ok -> SportsRules.read(league, reply.text, now, last)?.also { keep(it, activeLeagues()) }
                ?: failed(league, last, Failure.NO_ANSWER, went)
            is Reply.Failed -> when (reply.why) {
                Why.OFF -> null
                Why.OFFLINE -> failed(league, last, Failure.OFFLINE, went)
                Why.STATUS -> failed(
                    league,
                    last,
                    if (reply.status == 429) Failure.SLOW_DOWN else Failure.NO_ANSWER,
                    went,
                    reply.retryAfterSec ?: 0L,
                )
                Why.TIMEOUT, Why.TOO_LARGE, Why.UNREADABLE -> failed(league, last, Failure.NO_ANSWER, went)
            }
        }
    }

    fun failed(league: League, last: Scoreboard?, failure: Failure, went: Boolean, waitSec: Long = 0L): Scoreboard {
        val known = last?.takeIf { it.league == league } ?: Scoreboard(league)
        return known.copy(
            failure = failure,
            misses = known.misses + if (went) 1 else 0,
            retryAfterSec = waitSec,
        )
    }

    private fun keep(board: Scoreboard, active: Set<League>) {
        try {
            val kept = Kept.fetched(service)
            if (board.league in active) kept.write(board.league.code, SportsRules.keep(board), board.fetchedAt)
            kept.keepOnly(active.mapTo(HashSet()) { it.code })
        } catch (e: Exception) {
            // Disk unavailable; next start will fetch fresh.
        }
    }

    fun tidy(active: Set<League>) {
        try {
            Kept.fetched(service).keepOnly(active.mapTo(HashSet()) { it.code })
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun kept(league: League, now: Long): Refresher.Restored<Scoreboard>? = try {
        val kept = Kept.fetched(service)
        val entry = kept.read(league.code)
        val board = entry?.let { SportsRules.kept(it.text) }?.takeIf { it.league == league }
        if (entry != null && board == null) kept.remove(league.code)
        board?.let { Refresher.Restored(it, (now - it.fetchedAt).coerceAtLeast(0L)) }
    } catch (e: Exception) {
        null
    }
}
