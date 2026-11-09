package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.SportsRules.League
import com.meridian11.books.bentobar.items.SportsRules.Scoreboard
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import com.meridian11.books.bentobar.net.Why

// Requests and loading for the Sports scores item (ESPN scoreboard API). No Android, and no logging.

object SportsLoad {
    fun request(league: League) = Request(Host.ESPN, league.path)

    fun load(league: League, last: Scoreboard?, now: Long): Scoreboard? {
        val reply = Http.get(request(league))
        val good = (reply as? Reply.Ok)?.let { SportsRules.read(league, it.text, now) }
        if (good != null) return good
        val failed = reply as? Reply.Failed
        if (failed?.why == Why.OFF) return null
        val failure = when {
            failed?.why == Why.OFFLINE -> Failure.OFFLINE
            failed?.status == 429 -> Failure.SLOW_DOWN
            else -> Failure.NO_ANSWER
        }
        val known = last ?: Scoreboard(league)
        return known.copy(failure = failure)
    }
}
