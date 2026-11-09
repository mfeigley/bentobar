package com.meridian11.books.bentobar.items

import androidx.compose.runtime.Immutable
import com.meridian11.books.bentobar.data.ItemConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Pure rules for the Sports scores item: supported ESPN leagues, parsing ESPN scoreboard replies,
 * filtering by league and favorite teams, status bar rotation, and schedule-aware rate-limiting.
 * No Android imports, and no logging.
 */
object SportsRules {
    /** While a followed game is live, refresh every 3 minutes. */
    const val LIVE_REFRESH_MS = 3 * 60_000L
    /** When an upcoming game is within 30 minutes of kickoff (or recently past kickoff), check every 5 minutes. */
    const val SOON_REFRESH_MS = 5 * 60_000L
    /** When upcoming games are more than 30 minutes away, refresh every 30 minutes. */
    const val IDLE_REFRESH_MS = 30 * 60_000L
    /** When all games are final or none are scheduled today, refresh once an hour. */
    const val DONE_REFRESH_MS = 60 * 60_000L
    /** Minimum age before opening the popup menu refreshes a live or imminent scoreboard. */
    const val MENU_MS = 2 * 60_000L
    /** Minimum cooldown between manual Refresh clicks. */
    const val REFRESH_FLOOR_MS = 60_000L
    /** Base retry delay after a failed request; doubles on consecutive misses up to [MAX_RETRY_MS]. */
    const val RETRY_MS = 2 * 60_000L
    const val MAX_RETRY_MS = 15 * 60_000L
    /** Minimum wait when ESPN responds with HTTP 429 (rate-limited). */
    const val SLOW_MS = 15 * 60_000L
    /** Keep a game in the live rotation for 5 minutes after it initially transitions from LIVE to FINAL. */
    const val FINAL_HOLD_MS = 5 * 60_000L

    private const val SOON_AHEAD_MS = 30 * 60_000L
    private const val SOON_BEHIND_MS = 3 * 60 * 60_000L

    const val BAR_CHARS = 30
    const val MOST_LEAGUES = 4
    const val MOST_TEAMS = 10
    const val TURN_SEC = 5
    val TURNS = listOf(3, 5, 10, 30, 60)

    const val DEFAULT_LEAGUES = "NFL"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    enum class League(val code: String, val label: String, val sport: String, val slug: String) {
        NFL("NFL", "NFL", "football", "nfl"),
        NBA("NBA", "NBA", "basketball", "nba"),
        MLB("MLB", "MLB", "baseball", "mlb"),
        NHL("NHL", "NHL", "hockey", "nhl"),
        NCAAF("NCAAF", "NCAAF", "football", "college-football"),
        NCAAB("NCAAB", "NCAAB", "basketball", "mens-college-basketball"),
        WNBA("WNBA", "WNBA", "basketball", "wnba"),
        EPL("EPL", "Premier League", "soccer", "eng.1"),
        MLS("MLS", "MLS", "soccer", "usa.1"),
        UCL("UCL", "Champions League", "soccer", "uefa.champions");

        val path: String get() = "/apis/site/v2/sports/$sport/$slug/scoreboard"

        companion object {
            private val byCode = entries.associateBy { it.code.uppercase(Locale.ROOT) }
            fun of(code: String): League? = byCode[code.trim().uppercase(Locale.ROOT)]
        }
    }

    @Serializable
    enum class GameState { LIVE, FINAL, UPCOMING }

    @Serializable
    @Immutable
    data class Game(
        val id: String,
        val league: League,
        val awayAbbr: String,
        val awayName: String,
        val awayFullName: String,
        val awayScore: Int? = null,
        val awayRank: Int? = null,
        val homeAbbr: String,
        val homeName: String,
        val homeFullName: String,
        val homeScore: Int? = null,
        val homeRank: Int? = null,
        val state: GameState,
        val statusDetail: String,
        val shortStatus: String,
        val possessionAbbr: String? = null,
        val situationText: String? = null,
        val startEpochMs: Long? = null,
        val finalSinceMs: Long? = null,
        val webLink: String? = null,
    ) {
        val margin: Int? get() = if (awayScore != null && homeScore != null) abs(awayScore - homeScore) else null

        /** True while [state] is [GameState.LIVE], or for [FINAL_HOLD_MS] (5 minutes) after initially going final. */
        fun inLiveRotation(now: Long): Boolean =
            state == GameState.LIVE || (state == GameState.FINAL && finalSinceMs != null && now - finalSinceMs in 0..FINAL_HOLD_MS)

        fun matchesTeam(query: String): Boolean {
            val q = query.trim().uppercase(Locale.ROOT)
            if (q.isEmpty()) return false
            if (awayAbbr.uppercase(Locale.ROOT) == q || homeAbbr.uppercase(Locale.ROOT) == q) return true
            return awayName.uppercase(Locale.ROOT).contains(q) ||
                homeName.uppercase(Locale.ROOT).contains(q) ||
                awayFullName.uppercase(Locale.ROOT).contains(q) ||
                homeFullName.uppercase(Locale.ROOT).contains(q)
        }

        override fun toString() = "Game(league=${league.code}, state=$state)"
    }

    @Serializable
    @Immutable
    data class Scoreboard(
        val league: League,
        val games: List<Game> = emptyList(),
        val fetchedAt: Long = 0,
        val failure: Failure? = null,
        val misses: Int = 0,
        val retryAfterSec: Long = 0,
    ) {
        val hasLive: Boolean get() = games.any { it.state == GameState.LIVE }
        override fun toString() = "Scoreboard(league=${league.code}, games=${games.size}, failure=$failure)"
    }

    // ---- item configuration -----------------------------------------------------------------------

    fun leagues(item: ItemConfig): List<League> {
        val raw = item.options["leagues"] ?: DEFAULT_LEAGUES
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull(League::of).distinct().take(MOST_LEAGUES)
    }

    fun withLeagues(item: ItemConfig, list: List<League>): ItemConfig {
        val clean = list.distinct().take(MOST_LEAGUES)
        return item.with("leagues", if (clean.isEmpty()) "" else clean.joinToString(",") { it.code })
    }

    fun toggleLeague(item: ItemConfig, league: League): ItemConfig {
        val current = leagues(item)
        return if (league in current) {
            withLeagues(item, current - league)
        } else if (current.size < MOST_LEAGUES) {
            withLeagues(item, current + league)
        } else item
    }

    fun parseTeamToken(raw: String): String? {
        val cleaned = TextRules.oneLine(raw, 24).replace(",", " ").trim()
        return cleaned.takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
    }

    fun teams(item: ItemConfig): List<String> {
        val raw = item.options["teams"].orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull(::parseTeamToken)
            .distinctBy { it.uppercase(Locale.ROOT) }
            .take(MOST_TEAMS)
    }

    fun withTeams(item: ItemConfig, list: List<String>): ItemConfig {
        val clean = list.mapNotNull(::parseTeamToken)
            .distinctBy { it.uppercase(Locale.ROOT) }
            .take(MOST_TEAMS)
        return item.with("teams", if (clean.isEmpty()) null else clean.joinToString(","))
    }

    fun addTeam(item: ItemConfig, raw: String): ItemConfig {
        val token = parseTeamToken(raw) ?: return item
        val current = teams(item)
        if (current.size >= MOST_TEAMS || current.any { it.equals(token, ignoreCase = true) }) return item
        return withTeams(item, current + token)
    }

    fun removeTeam(item: ItemConfig, team: String): ItemConfig =
        withTeams(item, teams(item).filterNot { it.equals(team, ignoreCase = true) })

    fun liveOnly(item: ItemConfig): Boolean = item.optBool("liveOnly", false)

    fun withLiveOnly(item: ItemConfig, on: Boolean): ItemConfig =
        item.with("liveOnly", if (on) "true" else null)

    fun turnSec(item: ItemConfig): Int = item.optInt("turnSec", TURN_SEC).let { if (it in TURNS) it else TURN_SEC }

    fun pinnedIds(item: ItemConfig): Set<String> {
        val raw = item.options["pinned"].orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun withPinnedIds(item: ItemConfig, ids: Collection<String>): ItemConfig {
        val clean = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20)
        return item.with("pinned", if (clean.isEmpty()) null else clean.joinToString(","))
    }

    fun togglePinned(item: ItemConfig, gameId: String, liveGameIds: Set<String> = emptySet()): ItemConfig {
        val id = gameId.trim()
        if (id.isEmpty()) return item
        val current = pinnedIds(item).let { pinned ->
            if (liveGameIds.isEmpty()) pinned else pinned.filterTo(LinkedHashSet()) { it in liveGameIds || it == id }
        }
        val next = if (id in current) current - id else current + id
        return withPinnedIds(item, next)
    }

    fun clearPinned(item: ItemConfig): ItemConfig = item.with("pinned", null)

    // ---- parsing ESPN scoreboard JSON -------------------------------------------------------------

    fun read(league: League, jsonText: String, now: Long, last: Scoreboard? = null): Scoreboard? = try {
        val root = Json.parseToJsonElement(jsonText).jsonObject
        val events = root["events"] as? JsonArray ?: return null
        val prevById = last?.games?.associateBy { it.id }.orEmpty()
        val games = events.asSequence().take(60).mapNotNull { el ->
            parseEvent(league, el as? JsonObject ?: return@mapNotNull null, now, prevById)
        }.toList()
        Scoreboard(league, games, fetchedAt = now)
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    private fun parseEvent(league: League, ev: JsonObject, now: Long, prevById: Map<String, Game>): Game? {
        val id = ev["id"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: return null
        val comps = ev["competitions"] as? JsonArray
        val comp = comps?.firstOrNull() as? JsonObject ?: return null
        val competitors = comp["competitors"] as? JsonArray ?: return null
        if (competitors.size < 2) return null

        val c0 = competitors[0] as? JsonObject ?: return null
        val c1 = competitors[1] as? JsonObject ?: return null
        val awayObj = if (c0["homeAway"]?.jsonPrimitive?.content == "away") c0 else if (c1["homeAway"]?.jsonPrimitive?.content == "away") c1 else c1
        val homeObj = if (awayObj === c0) c1 else c0

        val awayTeam = awayObj["team"] as? JsonObject
        val homeTeam = homeObj["team"] as? JsonObject

        val awayAbbr = TextRules.oneLine(
            awayTeam?.get("abbreviation")?.jsonPrimitive?.content
                ?: awayTeam?.get("shortDisplayName")?.jsonPrimitive?.content.orEmpty(),
            6,
        ).uppercase(Locale.ROOT).ifEmpty { return null }

        val homeAbbr = TextRules.oneLine(
            homeTeam?.get("abbreviation")?.jsonPrimitive?.content
                ?: homeTeam?.get("shortDisplayName")?.jsonPrimitive?.content.orEmpty(),
            6,
        ).uppercase(Locale.ROOT).ifEmpty { return null }

        val awayName = TextRules.oneLine(
            awayTeam?.get("shortDisplayName")?.jsonPrimitive?.content
                ?: awayTeam?.get("displayName")?.jsonPrimitive?.content.orEmpty(),
            28,
        ).ifEmpty { awayAbbr }

        val homeName = TextRules.oneLine(
            homeTeam?.get("shortDisplayName")?.jsonPrimitive?.content
                ?: homeTeam?.get("displayName")?.jsonPrimitive?.content.orEmpty(),
            28,
        ).ifEmpty { homeAbbr }

        val awayFull = TextRules.oneLine(awayTeam?.get("displayName")?.jsonPrimitive?.content.orEmpty(), 40).ifEmpty { awayName }
        val homeFull = TextRules.oneLine(homeTeam?.get("displayName")?.jsonPrimitive?.content.orEmpty(), 40).ifEmpty { homeName }

        val statusObj = (comp["status"] as? JsonObject) ?: (ev["status"] as? JsonObject)
        val typeObj = statusObj?.get("type") as? JsonObject
        val rawState = typeObj?.get("state")?.jsonPrimitive?.content?.lowercase(Locale.ROOT)
        val completed = typeObj?.get("completed")?.jsonPrimitive?.booleanOrNull == true
        val state = when {
            rawState == "in" -> GameState.LIVE
            completed || rawState == "post" -> GameState.FINAL
            else -> GameState.UPCOMING
        }

        val awayScore = if (state == GameState.UPCOMING) null else awayObj["score"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
        val homeScore = if (state == GameState.UPCOMING) null else homeObj["score"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()

        val awayRank = (awayObj["curatedRank"] as? JsonObject)?.get("current")?.jsonPrimitive?.intOrNull?.takeIf { it in 1..25 }
        val homeRank = (homeObj["curatedRank"] as? JsonObject)?.get("current")?.jsonPrimitive?.intOrNull?.takeIf { it in 1..25 }

        val clock = statusObj?.get("displayClock")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val period = statusObj?.get("period")?.jsonPrimitive?.intOrNull ?: 0
        val shortDetail = TextRules.oneLine(
            typeObj?.get("shortDetail")?.jsonPrimitive?.content
                ?: typeObj?.get("detail")?.jsonPrimitive?.content
                ?: typeObj?.get("description")?.jsonPrimitive?.content.orEmpty(),
            36,
        )

        val awayId = awayTeam?.get("id")?.jsonPrimitive?.content ?: awayObj["id"]?.jsonPrimitive?.content
        val homeId = homeTeam?.get("id")?.jsonPrimitive?.content ?: homeObj["id"]?.jsonPrimitive?.content
        val sit = if (state == GameState.LIVE) comp["situation"] as? JsonObject else null
        val possId = sit?.get("possession")?.jsonPrimitive?.content
        val possessionAbbr = when {
            possId == null -> null
            possId == awayId -> awayAbbr
            possId == homeId -> homeAbbr
            else -> null
        }
        val situationText = sit?.let {
            TextRules.oneLine(
                it["shortDownDistanceText"]?.jsonPrimitive?.content
                    ?: it["downDistanceText"]?.jsonPrimitive?.content.orEmpty(),
                36,
            ).ifEmpty { null }
        }

        val dateStr = (comp["date"] ?: ev["date"])?.jsonPrimitive?.content
        val startEpochMs = dateStr?.let(::parseIsoEpochMs)
        val prev = prevById[id]
        val finalSinceMs = when {
            state != GameState.FINAL -> null
            prev?.state == GameState.LIVE -> now
            prev?.state == GameState.FINAL -> prev.finalSinceMs
            else -> null
        }
        val webLink = parseEspnLink(ev["links"] as? JsonArray)

        val shortStatus = compactStatus(league, state, clock, period, shortDetail)
        val detail = shortDetail.ifEmpty { shortStatus }

        return Game(
            id = id,
            league = league,
            awayAbbr = awayAbbr,
            awayName = awayName,
            awayFullName = awayFull,
            awayScore = awayScore,
            awayRank = awayRank,
            homeAbbr = homeAbbr,
            homeName = homeName,
            homeFullName = homeFull,
            homeScore = homeScore,
            homeRank = homeRank,
            state = state,
            statusDetail = detail,
            shortStatus = shortStatus,
            possessionAbbr = possessionAbbr,
            situationText = situationText,
            startEpochMs = startEpochMs,
            finalSinceMs = finalSinceMs,
            webLink = webLink,
        )
    }

    private fun parseEspnLink(links: JsonArray?): String? {
        if (links == null) return null
        for (el in links) {
            val obj = el as? JsonObject ?: continue
            val href = obj["href"]?.jsonPrimitive?.content?.trim() ?: continue
            if (isSafeEspnPage(href)) return href
        }
        return null
    }

    internal fun isSafeEspnPage(href: String): Boolean =
        href.startsWith("https://www.espn.com/") && href.length <= 200 && href.none { it.isWhitespace() || it.isISOControl() }

    /** Returns the ESPN game page address for [g], falling back to the canonical ESPN game path. */
    fun gamePage(g: Game): String {
        g.webLink?.takeIf(::isSafeEspnPage)?.let { return it }
        val cleanId = g.id.trim()
        if (cleanId.isNotEmpty() && cleanId.all(Char::isDigit)) {
            return if (g.league.sport == "soccer") {
                "https://www.espn.com/soccer/match/_/gameId/$cleanId"
            } else {
                "https://www.espn.com/${g.league.slug}/game/_/gameId/$cleanId"
            }
        }
        return "https://www.espn.com/"
    }

    internal fun parseIsoEpochMs(raw: String): Long? = try {
        OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    internal fun compactStatus(league: League, state: GameState, clock: String?, period: Int, shortDetail: String): String {
        return when (state) {
            GameState.FINAL -> if (shortDetail.contains("OT", ignoreCase = true)) "F/OT" else "F"
            GameState.UPCOMING -> shortDetail.substringAfter(" - ").trim().ifEmpty { shortDetail.ifEmpty { "Sched" } }
            GameState.LIVE -> {
                val lower = shortDetail.lowercase(Locale.ROOT)
                when {
                    lower.contains("halftime") || lower == "ht" || lower.contains("half") -> "HT"
                    lower.contains("delayed") -> "Delay"
                    league.sport == "baseball" -> {
                        val inning = period.takeIf { it > 0 }?.toString().orEmpty()
                        when {
                            lower.startsWith("top") && inning.isNotEmpty() -> "T$inning"
                            lower.startsWith("bot") && inning.isNotEmpty() -> "B$inning"
                            lower.startsWith("mid") && inning.isNotEmpty() -> "M$inning"
                            lower.startsWith("end") && inning.isNotEmpty() -> "E$inning"
                            else -> shortDetail.take(8)
                        }
                    }
                    league.sport == "soccer" -> shortDetail.take(8).ifEmpty { "Live" }
                    else -> {
                        val maxReg = when {
                            league == League.NCAAB -> 2
                            league.sport == "hockey" -> 3
                            else -> 4
                        }
                        val pLabel = when {
                            period <= 0 -> ""
                            period > maxReg -> if (period == maxReg + 1) "OT" else "${period - maxReg}OT"
                            league == League.NCAAB -> "${period}H"
                            league.sport == "hockey" -> "P$period"
                            else -> "Q$period"
                        }
                        if (lower.startsWith("end") && pLabel.isNotEmpty()) {
                            "End $pLabel"
                        } else if (!clock.isNullOrEmpty() && clock != "0:00" && clock != "0.0" && pLabel.isNotEmpty()) {
                            "$pLabel $clock"
                        } else {
                            pLabel.ifEmpty { shortDetail.take(10).ifEmpty { "Live" } }
                        }
                    }
                }
            }
        }
    }

    // ---- selecting, filtering, and formatting games -----------------------------------------------

    /**
     * Timestamp used to order [GameState.FINAL] games most-recently-ended first: uses [Game.finalSinceMs]
     * when the end time is known, falling back to [Game.startEpochMs] in reverse chronological order.
     */
    internal fun finalOrderKey(g: Game): Long = g.finalSinceMs ?: g.startEpochMs ?: Long.MIN_VALUE

    /**
     * All games across [leagues] matching [teams] (or all games when [teams] is empty), ordered with:
     * 1. Pinned games in the live rotation ([Game.inLiveRotation]),
     * 2. Unpinned games in the live rotation ([Game.inLiveRotation]),
     * 3. [GameState.FINAL] games ordered by most recently ended first (falling back to [Game.startEpochMs]
     *    in reverse chronological order when the end time is unknown),
     * 4. [GameState.UPCOMING] games in chronological order.
     */
    fun gamesFor(
        leagues: List<League>,
        teams: List<String>,
        boards: Map<League, Scoreboard>,
        pinnedIds: Set<String> = emptySet(),
        now: Long = 0L,
    ): List<Game> {
        val raw = leagues.flatMap { boards[it]?.games.orEmpty() }
        val filtered = if (teams.isEmpty()) raw else raw.filter { g -> teams.any { g.matchesTeam(it) } }
        return filtered.sortedWith(
            compareBy<Game> { g ->
                when {
                    g.inLiveRotation(now) && g.id in pinnedIds -> 0
                    g.inLiveRotation(now) -> 1
                    g.state == GameState.FINAL -> 2
                    else -> 3
                }
            }.thenComparator { a, b ->
                if (a.state == GameState.FINAL && b.state == GameState.FINAL) {
                    finalOrderKey(b).compareTo(finalOrderKey(a))
                } else {
                    (a.startEpochMs ?: Long.MAX_VALUE).compareTo(b.startEpochMs ?: Long.MAX_VALUE)
                }
            },
        )
    }

    /**
     * Games to rotate in the status bar:
     * - When one or more games in the live rotation are pinned in [pinnedIds], only those pinned games.
     * - When [liveOnly] is true, only games in the live rotation (including games within [FINAL_HOLD_MS] of going final).
     * - Otherwise, games in the live rotation when any is active, or all matching games when none is active.
     */
    fun barGames(
        games: List<Game>,
        liveOnly: Boolean,
        pinnedIds: Set<String> = emptySet(),
        now: Long = 0L,
    ): List<Game> {
        val live = games.filter { it.inLiveRotation(now) }
        val pinnedLive = if (pinnedIds.isEmpty()) emptyList() else live.filter { it.id in pinnedIds }
        return when {
            pinnedLive.isNotEmpty() -> pinnedLive
            liveOnly -> live
            live.isNotEmpty() -> live
            else -> games
        }
    }

    /** Formats one game for the status bar within [BAR_CHARS] characters. */
    fun barText(g: Game, startTimeText: String? = null): String {
        fun fits(s: String) = TextRules.fits(s, BAR_CHARS)
        return when (g.state) {
            GameState.LIVE -> {
                val a = g.awayScore ?: 0
                val h = g.homeScore ?: 0
                listOf(
                    "${g.awayAbbr} $a ${g.homeAbbr} $h · ${g.shortStatus}",
                    "${g.awayAbbr} $a-${h} ${g.homeAbbr} · ${g.shortStatus}",
                    "${g.awayAbbr} $a-${h} ${g.homeAbbr} ${g.shortStatus}",
                    "${g.awayAbbr} $a ${g.homeAbbr} $h ${g.shortStatus}",
                    "${g.awayAbbr} $a-$h ${g.homeAbbr}",
                ).firstOrNull(::fits) ?: "${g.awayAbbr} $a-$h ${g.homeAbbr} ${g.shortStatus}"
            }
            GameState.FINAL -> {
                val a = g.awayScore ?: 0
                val h = g.homeScore ?: 0
                listOf(
                    "${g.awayAbbr} $a ${g.homeAbbr} $h ${g.shortStatus}",
                    "${g.awayAbbr} $a-$h ${g.homeAbbr} F",
                    "${g.awayAbbr} $a-$h ${g.homeAbbr}",
                ).firstOrNull(::fits) ?: "${g.awayAbbr}-${g.homeAbbr}"
            }
            GameState.UPCOMING -> {
                val time = startTimeText?.takeIf { it.isNotEmpty() } ?: g.shortStatus
                listOfNotNull(
                    "${g.awayAbbr} @ ${g.homeAbbr} · $time".takeIf { time.isNotEmpty() },
                    "${g.awayAbbr}@${g.homeAbbr} $time".takeIf { time.isNotEmpty() },
                    "${g.awayAbbr} @ ${g.homeAbbr}",
                ).firstOrNull(::fits) ?: "${g.awayAbbr}@${g.homeAbbr}"
            }
        }
    }

    fun spokenGame(g: Game, startTimeText: String? = null): String = when (g.state) {
        GameState.LIVE -> "${g.awayName} ${g.awayScore ?: 0}, ${g.homeName} ${g.homeScore ?: 0}, ${g.statusDetail}"
        GameState.FINAL -> "${g.awayName} ${g.awayScore ?: 0}, ${g.homeName} ${g.homeScore ?: 0}, ${g.statusDetail}"
        GameState.UPCOMING -> "${g.awayName} at ${g.homeName}, ${startTimeText ?: g.statusDetail}"
    }

    // ---- rate-limiting and adaptive polling -------------------------------------------------------

    /** True if any upcoming game in [games] starts within 30 minutes or started within the last 3 hours. */
    fun hasStartingSoon(games: List<Game>, now: Long): Boolean = games.any { g ->
        g.state == GameState.UPCOMING && g.startEpochMs != null &&
            g.startEpochMs - now <= SOON_AHEAD_MS && now - g.startEpochMs <= SOON_BEHIND_MS
    }

    /**
     * How long [board] stays fresh before ESPN is asked again:
     * - Rate-limited (`SLOW_DOWN`): at least 15 minutes (or `retryAfterSec` if longer).
     * - Transient failure: exponential backoff (`2m`, `4m`, `8m`, up to `15m`).
     * - Live game (matching [teams] when filtered): every 3 minutes ([LIVE_REFRESH_MS]).
     * - Upcoming game starting within 30 minutes: every 5 minutes ([SOON_REFRESH_MS]).
     * - Later upcoming games today: every 30 minutes ([IDLE_REFRESH_MS]).
     * - All games final or none scheduled: every 60 minutes ([DONE_REFRESH_MS]).
     */
    fun every(board: Scoreboard, now: Long = board.fetchedAt, teams: List<String> = emptyList()): Long {
        if (board.failure == Failure.SLOW_DOWN) {
            return maxOf(SLOW_MS, board.retryAfterSec * 1000L)
        }
        if (board.failure != null) {
            val shift = (board.misses - 1).coerceIn(0, 3)
            return minOf(MAX_RETRY_MS, RETRY_MS shl shift)
        }
        val relevant = if (teams.isEmpty()) board.games else board.games.filter { g -> teams.any { g.matchesTeam(it) } }
        return when {
            relevant.any { it.state == GameState.LIVE } -> LIVE_REFRESH_MS
            hasStartingSoon(relevant, now) -> SOON_REFRESH_MS
            relevant.any { it.state == GameState.UPCOMING } -> IDLE_REFRESH_MS
            else -> DONE_REFRESH_MS
        }
    }

    /** Minimum wait before a manual Refresh is allowed to ask ESPN again for [board]. */
    fun againAfter(board: Scoreboard?): Long =
        maxOf(if (board?.failure == Failure.SLOW_DOWN) SLOW_MS else REFRESH_FLOOR_MS, (board?.retryAfterSec ?: 0L) * 1000L)

    // ---- on-device persistence (parsed model only, never raw JSON) --------------------------------

    fun keep(board: Scoreboard): String = json.encodeToString(Scoreboard.serializer(), board)

    fun kept(text: String): Scoreboard? = try {
        json.decodeFromString(Scoreboard.serializer(), text).copy(failure = null, misses = 0, retryAfterSec = 0)
            .takeIf { it.fetchedAt > 0 }
    } catch (e: Exception) {
        null
    }

    // ---- debug samples (`./bento debug sports stage live|final|upcoming|offline`) ------------------

    fun sample(name: String, league: League, now: Long): Scoreboard? = when (name) {
        "live" -> Scoreboard(
            league = league,
            games = listOf(
                Game(
                    id = "1",
                    league = league,
                    awayAbbr = "TB",
                    awayName = "Buccaneers",
                    awayFullName = "Tampa Bay Buccaneers",
                    awayScore = 17,
                    homeAbbr = "CHI",
                    homeName = "Bears",
                    homeFullName = "Chicago Bears",
                    homeScore = 20,
                    state = GameState.LIVE,
                    statusDetail = "4:12 - 4th Quarter",
                    shortStatus = "Q4 4:12",
                    possessionAbbr = "CHI",
                    situationText = "2nd & 6 at TB 38",
                    startEpochMs = now - 7_200_000,
                ),
                Game(
                    id = "2",
                    league = league,
                    awayAbbr = "KC",
                    awayName = "Chiefs",
                    awayFullName = "Kansas City Chiefs",
                    awayScore = 24,
                    homeAbbr = "BUF",
                    homeName = "Bills",
                    homeFullName = "Buffalo Bills",
                    homeScore = 24,
                    state = GameState.LIVE,
                    statusDetail = "1:55 - 4th Quarter",
                    shortStatus = "Q4 1:55",
                    possessionAbbr = "KC",
                    situationText = "1st & 10 at BUF 42",
                    startEpochMs = now - 7_200_000,
                ),
            ),
            fetchedAt = now - 25_000,
        )
        "final" -> Scoreboard(
            league = league,
            games = listOf(
                Game(
                    id = "3",
                    league = league,
                    awayAbbr = "DAL",
                    awayName = "Cowboys",
                    awayFullName = "Dallas Cowboys",
                    awayScore = 27,
                    homeAbbr = "IND",
                    homeName = "Colts",
                    homeFullName = "Indianapolis Colts",
                    homeScore = 23,
                    state = GameState.FINAL,
                    statusDetail = "Final",
                    shortStatus = "F",
                    startEpochMs = now - 14_400_000,
                ),
                Game(
                    id = "4",
                    league = league,
                    awayAbbr = "CIN",
                    awayName = "Bengals",
                    awayFullName = "Cincinnati Bengals",
                    awayScore = 34,
                    homeAbbr = "ATL",
                    homeName = "Falcons",
                    homeFullName = "Atlanta Falcons",
                    homeScore = 17,
                    state = GameState.FINAL,
                    statusDetail = "Final",
                    shortStatus = "F",
                    startEpochMs = now - 18_000_000,
                ),
            ),
            fetchedAt = now - 120_000,
        )
        "upcoming" -> Scoreboard(
            league = league,
            games = listOf(
                Game(
                    id = "5",
                    league = league,
                    awayAbbr = "SF",
                    awayName = "49ers",
                    awayFullName = "San Francisco 49ers",
                    awayScore = null,
                    homeAbbr = "SEA",
                    homeName = "Seahawks",
                    homeFullName = "Seattle Seahawks",
                    homeScore = null,
                    state = GameState.UPCOMING,
                    statusDetail = "7:20 PM",
                    shortStatus = "7:20 PM",
                    startEpochMs = now + 7_200_000,
                ),
            ),
            fetchedAt = now - 60_000,
        )
        "offline" -> Scoreboard(
            league = league,
            games = listOf(
                Game(
                    id = "1",
                    league = league,
                    awayAbbr = "TB",
                    awayName = "Buccaneers",
                    awayFullName = "Tampa Bay Buccaneers",
                    awayScore = 17,
                    homeAbbr = "CHI",
                    homeName = "Bears",
                    homeFullName = "Chicago Bears",
                    homeScore = 20,
                    state = GameState.LIVE,
                    statusDetail = "4:12 - 4th Quarter",
                    shortStatus = "Q4 4:12",
                    startEpochMs = now - 7_200_000,
                ),
            ),
            fetchedAt = now - 600_000,
            failure = Failure.OFFLINE,
        )
        else -> null
    }
}
