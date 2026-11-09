package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.SportsRules.GameState
import com.meridian11.books.bentobar.items.SportsRules.League
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsRulesTest {
    private val scoreboardJson = """
        {
          "events": [
            {
              "id": "401671701",
              "date": "2026-11-08T18:00Z",
              "competitions": [
                {
                  "date": "2026-11-08T18:00Z",
                  "status": {
                    "displayClock": "4:12",
                    "period": 4,
                    "type": {
                      "state": "in",
                      "completed": false,
                      "shortDetail": "4:12 - 4th"
                    }
                  },
                  "situation": {
                    "possession": "3",
                    "shortDownDistanceText": "2nd & 6 at TB 38"
                  },
                  "competitors": [
                    {
                      "id": "3",
                      "homeAway": "home",
                      "score": "20",
                      "curatedRank": { "current": 99 },
                      "team": {
                        "id": "3",
                        "abbreviation": "CHI",
                        "shortDisplayName": "Bears",
                        "displayName": "Chicago Bears"
                      }
                    },
                    {
                      "id": "27",
                      "homeAway": "away",
                      "score": "17",
                      "curatedRank": { "current": 12 },
                      "team": {
                        "id": "27",
                        "abbreviation": "TB",
                        "shortDisplayName": "Buccaneers",
                        "displayName": "Tampa Bay Buccaneers"
                      }
                    }
                  ]
                }
              ]
            },
            {
              "id": "401671702",
              "date": "2026-11-08T17:00Z",
              "competitions": [
                {
                  "date": "2026-11-08T17:00Z",
                  "status": {
                    "displayClock": "0:00",
                    "period": 4,
                    "type": {
                      "state": "post",
                      "completed": true,
                      "shortDetail": "Final"
                    }
                  },
                  "competitors": [
                    {
                      "id": "11",
                      "homeAway": "home",
                      "score": "23",
                      "team": {
                        "id": "11",
                        "abbreviation": "IND",
                        "shortDisplayName": "Colts",
                        "displayName": "Indianapolis Colts"
                      }
                    },
                    {
                      "id": "6",
                      "homeAway": "away",
                      "score": "27",
                      "team": {
                        "id": "6",
                        "abbreviation": "DAL",
                        "shortDisplayName": "Cowboys",
                        "displayName": "Dallas Cowboys"
                      }
                    }
                  ]
                }
              ]
            },
            {
              "id": "401671703",
              "date": "2026-11-09T01:20Z",
              "competitions": [
                {
                  "date": "2026-11-09T01:20Z",
                  "status": {
                    "displayClock": "0:00",
                    "period": 0,
                    "type": {
                      "state": "pre",
                      "completed": false,
                      "shortDetail": "11/8 - 8:20 PM EST"
                    }
                  },
                  "competitors": [
                    {
                      "id": "26",
                      "homeAway": "home",
                      "score": "0",
                      "team": {
                        "id": "26",
                        "abbreviation": "SEA",
                        "shortDisplayName": "Seahawks",
                        "displayName": "Seattle Seahawks"
                      }
                    },
                    {
                      "id": "25",
                      "homeAway": "away",
                      "score": "0",
                      "team": {
                        "id": "25",
                        "abbreviation": "SF",
                        "shortDisplayName": "49ers",
                        "displayName": "San Francisco 49ers"
                      }
                    }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test fun parsesEspnScoreboardLiveFinalAndUpcomingGames() {
        val board = SportsRules.read(League.NFL, scoreboardJson, now = 100_000L)
        assertNotNull(board)
        board!!
        assertEquals(League.NFL, board.league)
        assertEquals(3, board.games.size)
        assertTrue(board.hasLive)

        val live = board.games[0]
        assertEquals(GameState.LIVE, live.state)
        assertEquals("TB", live.awayAbbr)
        assertEquals(17, live.awayScore)
        assertEquals(12, live.awayRank)
        assertEquals("CHI", live.homeAbbr)
        assertEquals(20, live.homeScore)
        assertNull(live.homeRank) // 99 is unranked
        assertEquals("Q4 4:12", live.shortStatus)
        assertEquals("CHI", live.possessionAbbr)
        assertEquals("2nd & 6 at TB 38", live.situationText)

        val fin = board.games[1]
        assertEquals(GameState.FINAL, fin.state)
        assertEquals("DAL", fin.awayAbbr)
        assertEquals(27, fin.awayScore)
        assertEquals("IND", fin.homeAbbr)
        assertEquals(23, fin.homeScore)
        assertEquals("F", fin.shortStatus)

        val up = board.games[2]
        assertEquals(GameState.UPCOMING, up.state)
        assertEquals("SF", up.awayAbbr)
        assertNull(up.awayScore)
        assertEquals("SEA", up.homeAbbr)
        assertNull(up.homeScore)
        assertNotNull(up.startEpochMs)
        assertEquals("https://www.espn.com/nfl/game/_/gameId/401671703", SportsRules.gamePage(up))
    }

    @Test fun rejectsMalformedJsonAndHandlesEmptyEvents() {
        assertNull(SportsRules.read(League.NFL, "not json", 10_000L))
        assertNull(SportsRules.read(League.NFL, """{"season":{}}""", 10_000L))
        val empty = SportsRules.read(League.NBA, """{"events":[]}""", 10_000L)
        assertNotNull(empty)
        assertEquals(0, empty!!.games.size)
        assertFalse(empty.hasLive)
    }

    @Test fun filtersGamesByTeamAndSelectsBarGames() {
        val board = SportsRules.read(League.NFL, scoreboardJson, now = 100_000L)!!
        val boards = mapOf(League.NFL to board)

        val all = SportsRules.gamesFor(listOf(League.NFL), emptyList(), boards)
        assertEquals(listOf("TB", "DAL", "SF"), all.map { it.awayAbbr })

        // When any game is live, barGames returns live games by default
        assertEquals(listOf("TB"), SportsRules.barGames(all, liveOnly = false).map { it.awayAbbr })

        // Team filter by abbreviation or name
        val cowboysAndNiners = SportsRules.gamesFor(listOf(League.NFL), listOf("DAL", "49ers"), boards)
        assertEquals(listOf("DAL", "SF"), cowboysAndNiners.map { it.awayAbbr })
        // None of those two is live: liveOnly=false shows both, liveOnly=true shows none
        assertEquals(2, SportsRules.barGames(cowboysAndNiners, liveOnly = false).size)
        assertEquals(0, SportsRules.barGames(cowboysAndNiners, liveOnly = true).size)
    }

    @Test fun formatsBarTextWithinTwentyCharactersAndChoosesScheduleAwareRefreshIntervals() {
        val board = SportsRules.read(League.NFL, scoreboardJson, now = 100_000L)!!
        for (g in board.games) {
            val text = SportsRules.barText(g, "7:20 PM")
            assertTrue("barText too long: $text", TextRules.fits(text, SportsRules.BAR_CHARS))
        }
        assertEquals("TB 17 CHI 20 · Q4 4:12".takeIf { TextRules.fits(it, SportsRules.BAR_CHARS) } ?: "TB 17-20 CHI Q4 4:12", SportsRules.barText(board.games[0]))
        assertEquals("DAL 27 IND 23 F", SportsRules.barText(board.games[1]))
        assertEquals("SF @ SEA · 7:20 PM", SportsRules.barText(board.games[2], "7:20 PM"))

        // Live game present -> 3 minutes
        assertEquals(SportsRules.LIVE_REFRESH_MS, SportsRules.every(board, now = 100_000L))
        // When filtering only for DAL (whose game is FINAL), interval drops to 60 minutes (DONE_REFRESH_MS) even though TB@CHI is live!
        assertEquals(SportsRules.DONE_REFRESH_MS, SportsRules.every(board, now = 100_000L, teams = listOf("DAL")))

        // Upcoming game starting in 15 minutes -> SOON_REFRESH_MS (5 minutes)
        val kickMs = board.games[2].startEpochMs!!
        assertEquals(SportsRules.SOON_REFRESH_MS, SportsRules.every(board, now = kickMs - 15 * 60_000L, teams = listOf("SF")))
        // Upcoming game starting in 3 hours -> IDLE_REFRESH_MS (30 minutes)
        assertEquals(SportsRules.IDLE_REFRESH_MS, SportsRules.every(board, now = kickMs - 3 * 3600_000L, teams = listOf("SF")))

        // Exponential backoff on consecutive misses
        assertEquals(SportsRules.RETRY_MS, SportsRules.every(board.copy(failure = Failure.OFFLINE, misses = 1)))
        assertEquals(SportsRules.RETRY_MS * 2, SportsRules.every(board.copy(failure = Failure.OFFLINE, misses = 2)))
        assertEquals(SportsRules.MAX_RETRY_MS, SportsRules.every(board.copy(failure = Failure.OFFLINE, misses = 5)))

        // Rate-limited (429): respects SLOW_MS and Retry-After header
        assertEquals(SportsRules.SLOW_MS, SportsRules.every(board.copy(failure = Failure.SLOW_DOWN)))
        assertEquals(1800_000L, SportsRules.every(board.copy(failure = Failure.SLOW_DOWN, retryAfterSec = 1800)))
        assertEquals(SportsRules.REFRESH_FLOOR_MS, SportsRules.againAfter(board))
        assertEquals(1800_000L, SportsRules.againAfter(board.copy(failure = Failure.SLOW_DOWN, retryAfterSec = 1800)))
    }

    @Test fun roundTripsKeptScoreboardWithoutFailureState() {
        val board = SportsRules.read(League.NFL, scoreboardJson, now = 100_000L)!!
            .copy(failure = Failure.OFFLINE, misses = 3, retryAfterSec = 120)
        val encoded = SportsRules.keep(board)
        val restored = SportsRules.kept(encoded)
        assertNotNull(restored)
        assertEquals(League.NFL, restored!!.league)
        assertEquals(3, restored.games.size)
        assertNull(restored.failure)
        assertEquals(0, restored.misses)
        assertEquals(0L, restored.retryAfterSec)
    }

    @Test fun managesLeaguesAndTeamsInItemConfig() {
        var item = ItemConfig("s1", "sports")
        assertEquals(listOf(League.NFL), SportsRules.leagues(item))

        item = SportsRules.toggleLeague(item, League.NBA)
        assertEquals(listOf(League.NFL, League.NBA), SportsRules.leagues(item))

        item = SportsRules.addTeam(item, " bears ")
        item = SportsRules.addTeam(item, "KC")
        assertEquals(listOf("bears", "KC"), SportsRules.teams(item))

        item = SportsRules.removeTeam(item, "BEARS")
        assertEquals(listOf("KC"), SportsRules.teams(item))
    }
}
