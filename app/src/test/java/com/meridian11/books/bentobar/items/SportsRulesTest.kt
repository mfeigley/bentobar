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

    @Test fun formatsBarTextWithClockAndQuarterAndChoosesScheduleAwareRefreshIntervals() {
        val board = SportsRules.read(League.NFL, scoreboardJson, now = 100_000L)!!
        for (g in board.games) {
            val text = SportsRules.barText(g, "7:20 PM")
            assertTrue("barText too long: $text", TextRules.fits(text, SportsRules.BAR_CHARS))
        }
        assertEquals("TB 17 CHI 20 · Q4 4:12", SportsRules.barText(board.games[0]))
        assertEquals("DAL 27 IND 23 F", SportsRules.barText(board.games[1]))
        assertEquals("SF @ SEA · 7:20 PM", SportsRules.barText(board.games[2], "7:20 PM"))

        // High-scoring NBA game with double-digit clock still includes quarter and clock
        val nbaLive = board.games[0].copy(
            league = League.NBA,
            awayAbbr = "BOS",
            awayScore = 112,
            homeAbbr = "MIL",
            homeScore = 104,
            shortStatus = "Q4 11:42",
        )
        assertEquals("BOS 112 MIL 104 · Q4 11:42", SportsRules.barText(nbaLive))

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

        // Pinning a single live game shows only that game; pinning multiple cycles only through the pinned games
        val sampleLive = SportsRules.sample("live", League.NFL, now = 100_000L)!!
        val boards = mapOf(League.NFL to sampleLive)
        item = SportsRules.togglePinned(item, "2", setOf("1", "2"))
        assertEquals(setOf("2"), SportsRules.pinnedIds(item))
        val ordered = SportsRules.gamesFor(listOf(League.NFL), emptyList(), boards, SportsRules.pinnedIds(item))
        assertEquals(listOf("2", "1"), ordered.map { it.id })
        assertEquals(listOf("2"), SportsRules.barGames(ordered, liveOnly = false, pinnedIds = SportsRules.pinnedIds(item)).map { it.id })

        item = SportsRules.togglePinned(item, "1", setOf("1", "2"))
        assertEquals(setOf("2", "1"), SportsRules.pinnedIds(item))
        assertEquals(2, SportsRules.barGames(ordered, liveOnly = false, pinnedIds = SportsRules.pinnedIds(item)).size)
    }

    @Test fun holdsNewlyFinalGamesInLiveRotationForFiveMinutesAndOrdersFinalGamesReverseChronologically() {
        val initial = SportsRules.read(League.NFL, scoreboardJson, now = 100_000L)!!
        assertNull(initial.games[1].finalSinceMs) // already final on first read -> end time unknown

        // Game 401671701 (TB @ CHI) transitions from LIVE ("in") to FINAL ("post") at t = 200_000L
        val afterFinalJson = scoreboardJson
            .replace(""""state": "in"""", """"state": "post"""")
            .replace(""""completed": false,""", """"completed": true,""")
        val updated = SportsRules.read(League.NFL, afterFinalJson, now = 200_000L, last = initial)!!
        val newlyFinal = updated.games.first { it.id == "401671701" }
        val olderFinal = updated.games.first { it.id == "401671702" }
        assertEquals(GameState.FINAL, newlyFinal.state)
        assertEquals(200_000L, newlyFinal.finalSinceMs)
        assertNull(olderFinal.finalSinceMs)

        // Preserves finalSinceMs on subsequent refreshes
        val refreshedAgain = SportsRules.read(League.NFL, afterFinalJson, now = 260_000L, last = updated)!!
        assertEquals(200_000L, refreshedAgain.games.first { it.id == "401671701" }.finalSinceMs)

        // At +4 minutes (within 5-minute hold), newlyFinal stays in the live rotation
        val at4Min = 200_000L + 4 * 60_000L
        val gamesAt4Min = SportsRules.gamesFor(listOf(League.NFL), emptyList(), mapOf(League.NFL to updated), now = at4Min)
        assertEquals(listOf("TB"), SportsRules.barGames(gamesAt4Min, liveOnly = true, now = at4Min).map { it.awayAbbr })
        assertEquals(listOf("TB"), SportsRules.barGames(gamesAt4Min, liveOnly = false, now = at4Min).map { it.awayAbbr })

        // At +6 minutes (past 5-minute hold), newlyFinal leaves the live rotation
        val at6Min = 200_000L + 6 * 60_000L
        val gamesAt6Min = SportsRules.gamesFor(listOf(League.NFL), emptyList(), mapOf(League.NFL to updated), now = at6Min)
        assertEquals(emptyList<String>(), SportsRules.barGames(gamesAt6Min, liveOnly = true, now = at6Min).map { it.awayAbbr })

        // Final games with unknown end times across selected leagues are ordered by start time in reverse chronological order
        val nflFinal = SportsRules.sample("final", League.NFL, now = 1_000_000_000L)!!
        val nbaFinal = SportsRules.Scoreboard(
            league = League.NBA,
            games = listOf(
                nflFinal.games[0].copy(
                    id = "nba1",
                    league = League.NBA,
                    awayAbbr = "BOS",
                    homeAbbr = "NYK",
                    startEpochMs = 1_000_000_000L - 10_800_000L, // 3h ago (more recent than DAL 4h ago and CIN 5h ago)
                    finalSinceMs = null,
                ),
            ),
            fetchedAt = 1_000_000_000L,
        )
        val multiFinal = SportsRules.gamesFor(
            listOf(League.NFL, League.NBA),
            emptyList(),
            mapOf(League.NFL to nflFinal, League.NBA to nbaFinal),
            now = 1_000_000_000L,
        )
        assertEquals(listOf("BOS", "DAL", "CIN"), multiFinal.map { it.awayAbbr })
    }

    @Test fun sortsLargeMixedLiveAndHeldFinalScoreboardsWithoutComparatorViolation() {
        val now = 2_000_000_000L
        val base = SportsRules.sample("live", League.NCAAB, now)!!.games.first()
        val games = (0 until 64).map { i ->
            val state = when (i % 4) {
                0 -> GameState.LIVE
                1 -> GameState.FINAL // held in live rotation (within 5-minute window)
                2 -> GameState.FINAL // older final (outside live rotation)
                else -> GameState.UPCOMING
            }
            val finalSince = when (i % 4) {
                1 -> now - (i * 3_000L) // within 5m hold
                2 -> if (i % 8 == 2) now - 20 * 60_000L - i * 1_000L else null
                else -> null
            }
            val start = if (i % 11 == 0) null else now - (64 - i) * 60_000L
            base.copy(
                id = "g$i",
                state = state,
                startEpochMs = start,
                finalSinceMs = finalSince,
            )
        }
        val pinned = setOf("g0", "g1", "g5", "g8")
        val zone = java.time.ZoneId.of("America/Chicago")
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val rng = java.util.Random(42L)
        repeat(25) {
            val shuffled = games.shuffled(rng)
            val board = SportsRules.Scoreboard(League.NCAAB, shuffled, fetchedAt = now)
            val sorted = SportsRules.gamesFor(
                listOf(League.NCAAB),
                emptyList(),
                mapOf(League.NCAAB to board),
                pinnedIds = pinned,
                now = now,
                zone = zone,
            )
            assertEquals(64, sorted.size)
            val buckets = sorted.map { g -> SportsRules.sortBucket(g, pinned, now, today, zone) }
            assertEquals(buckets.sorted(), buckets)
        }
    }

    @Test fun showsUpcomingGamesLaterTodayBeforeYesterdayFinalGames() {
        val zone = java.time.ZoneId.of("America/Chicago")
        val now = java.time.ZonedDateTime.of(2026, 11, 9, 18, 30, 0, 0, zone).toInstant().toEpochMilli()
        val template = SportsRules.sample("final", League.NFL, now)!!.games.first()

        val sundayEarlyFinal = template.copy(
            id = "sun1",
            awayAbbr = "DAL",
            homeAbbr = "IND",
            state = GameState.FINAL,
            startEpochMs = java.time.ZonedDateTime.of(2026, 11, 8, 12, 0, 0, 0, zone).toInstant().toEpochMilli(),
            finalSinceMs = null,
        )
        val sundayNightFinal = template.copy(
            id = "sun2",
            awayAbbr = "SF",
            homeAbbr = "SEA",
            state = GameState.FINAL,
            startEpochMs = java.time.ZonedDateTime.of(2026, 11, 8, 19, 20, 0, 0, zone).toInstant().toEpochMilli(),
            // Even if finalSinceMs trickled just past midnight, its scheduled date was yesterday (Nov 8).
            finalSinceMs = java.time.ZonedDateTime.of(2026, 11, 9, 0, 10, 0, 0, zone).toInstant().toEpochMilli(),
        )
        val mondayAfternoonFinal = template.copy(
            id = "mon_fin",
            league = League.UCL,
            awayAbbr = "ARS",
            homeAbbr = "BAY",
            state = GameState.FINAL,
            startEpochMs = java.time.ZonedDateTime.of(2026, 11, 9, 14, 0, 0, 0, zone).toInstant().toEpochMilli(),
            finalSinceMs = null,
        )
        val mondayNightUpcoming1 = template.copy(
            id = "mon_up1",
            awayAbbr = "CIN",
            homeAbbr = "BAL",
            awayScore = null,
            homeScore = null,
            state = GameState.UPCOMING,
            startEpochMs = java.time.ZonedDateTime.of(2026, 11, 9, 19, 15, 0, 0, zone).toInstant().toEpochMilli(),
        )
        val mondayNightUpcoming2 = template.copy(
            id = "mon_up2",
            league = League.NBA,
            awayAbbr = "LAL",
            homeAbbr = "DEN",
            awayScore = null,
            homeScore = null,
            state = GameState.UPCOMING,
            startEpochMs = java.time.ZonedDateTime.of(2026, 11, 9, 21, 0, 0, 0, zone).toInstant().toEpochMilli(),
        )
        val tuesdayUpcoming = template.copy(
            id = "tue_up",
            league = League.NBA,
            awayAbbr = "BOS",
            homeAbbr = "NYK",
            awayScore = null,
            homeScore = null,
            state = GameState.UPCOMING,
            startEpochMs = java.time.ZonedDateTime.of(2026, 11, 10, 18, 30, 0, 0, zone).toInstant().toEpochMilli(),
        )

        val boards = mapOf(
            League.NFL to SportsRules.Scoreboard(League.NFL, listOf(sundayEarlyFinal, sundayNightFinal, mondayNightUpcoming1), fetchedAt = now),
            League.NBA to SportsRules.Scoreboard(League.NBA, listOf(mondayNightUpcoming2, tuesdayUpcoming), fetchedAt = now),
            League.UCL to SportsRules.Scoreboard(League.UCL, listOf(mondayAfternoonFinal), fetchedAt = now),
        )
        val ordered = SportsRules.gamesFor(
            listOf(League.NFL, League.NBA, League.UCL),
            emptyList(),
            boards,
            now = now,
            zone = zone,
        )
        assertEquals(
            listOf("mon_fin", "mon_up1", "mon_up2", "sun2", "sun1", "tue_up"),
            ordered.map { it.id },
        )
    }
}
