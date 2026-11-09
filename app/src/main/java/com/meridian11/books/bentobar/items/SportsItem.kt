package com.meridian11.books.bentobar.items

import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.SportsRules.GameState
import com.meridian11.books.bentobar.items.SportsRules.League
import com.meridian11.books.bentobar.items.SportsRules.Scoreboard
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import java.time.ZoneId

/**
 * Sports scores: live, final and upcoming games from ESPN (`site.api.espn.com`, no key required),
 * rotating followed leagues and teams in the status bar with full game details in the menu.
 * No logging.
 */
object SportsItem : ItemType("sports", R.string.item_sports_title, Sym.STAR, R.string.item_sports_desc) {
    override val menuWidthDp = 340
    override val online = Online.Service.ESPN
    override val canBeActive = true
    override val trigger = Trigger(R.string.trigger_sports, R.string.trigger_sports_short)

    internal val boards: Refresher<League, Scoreboard> = Background.refresher(
        type = type,
        service = online,
        every = { league, b ->
            val teams = SportsLoad.followedTeamsFor(league, Store.config.value.items)
            SportsRules.every(b, Now.wall(), teams)
        },
        restore = { league -> SportsLoad.kept(league, Now.wall()) },
        load = { league, last ->
            SportsLoad.load(league, last, Now.wall()) { SportsLoad.leagues(Store.config.value.items) }
        },
    )

    @Volatile private var stagedName: String? = null

    private fun firstShown(): ItemConfig? = Store.config.value.items.firstOrNull { it.type == type && it.section != Section.OFF }

    internal fun isStaged(item: ItemConfig): Boolean = stagedName != null && firstShown()?.id == item.id

    internal fun boardsFor(item: ItemConfig, leagues: List<League> = SportsRules.leagues(item), ask: Boolean = true): Map<League, Scoreboard> {
        val sName = stagedName
        if (sName != null && firstShown()?.id == item.id) {
            val now = Now.wall()
            return leagues.mapNotNull { l -> SportsRules.sample(sName, l, now)?.let { l to it } }.toMap()
        }
        if (!Online.on(online)) return emptyMap()
        if (ask && item.section != Section.OFF) leagues.forEach { boards.want(it) }
        return leagues.mapNotNull { l -> boards.peek(l)?.let { l to it } }.toMap()
    }

    /**
     * When the popup menu opens: only trigger an early refresh if a game is currently live or
     * starting within 30 minutes and the snapshot is older than [SportsRules.MENU_MS] (2 minutes).
     * Otherwise rely on the normal schedule-aware refresh window.
     */
    internal fun opened(item: ItemConfig) {
        if (isStaged(item) || item.section == Section.OFF || !Online.on(online)) return
        val now = Now.wall()
        val teams = SportsRules.teams(item)
        for (l in SportsRules.leagues(item)) {
            val b = boards.peek(l)
            if (b == null) {
                boards.want(l)
            } else if (b.failure == null) {
                val relevant = if (teams.isEmpty()) b.games else b.games.filter { g -> teams.any { g.matchesTeam(it) } }
                val activeOrSoon = relevant.any { it.state == GameState.LIVE } || SportsRules.hasStartingSoon(relevant, now)
                if (activeOrSoon) {
                    boards.refresh(l, floorMs = SportsRules.MENU_MS)
                } else {
                    boards.want(l)
                }
            }
        }
    }

    /** Manual Refresh in the menu: guarded by [SportsRules.againAfter] (at least 60s, or 15m on HTTP 429). */
    internal fun refresh(item: ItemConfig) {
        if (stagedName != null || !Online.on(online)) return
        for (l in SportsRules.leagues(item)) {
            boards.refresh(l, floorMs = SportsRules.againAfter(boards.peek(l)))
        }
    }

    private var seenItems: List<ItemConfig>? = null
    private val tidying = Background.executor(type)

    private fun tidy() {
        val items = Store.config.value.items
        if (items === seenItems) return
        seenItems = items
        val active = SportsLoad.leagues(items)
        boards.keepOnly(active)
        if (Online.setUp(online)) tidying.execute { SportsLoad.tidy(active) }
    }

    override fun sample(now: Long) = tidy()

    override fun onIdle() = tidy()

    internal fun formatStartTime(epochMs: Long?): String? {
        if (epochMs == null) return null
        return runCatching { WeatherItem.times().clock(epochMs, ZoneId.systemDefault()) }.getOrNull()
    }

    override fun state(item: ItemConfig): ItemState {
        val leagues = SportsRules.leagues(item)
        if (!Online.on(online) && !isStaged(item)) {
            val d = Env.str(if (Online.setUp(online)) R.string.sports_desc_off else R.string.sports_desc_not_set_up)
            return ItemState(icon = Sym.STAR, filled = false, text = null, desc = d, tooltip = d)
        }
        if (leagues.isEmpty()) {
            val d = Env.str(R.string.sports_none_yet)
            return ItemState(icon = Sym.STAR, filled = false, text = null, desc = d, tooltip = d)
        }
        val bMap = boardsFor(item, leagues, ask = item.section != Section.OFF)
        if (bMap.isEmpty() || bMap.values.all { it.fetchedAt == 0L && it.failure == null }) {
            val d = Env.str(R.string.sports_desc_loading)
            return ItemState(icon = Sym.STAR, filled = false, text = null, desc = d, tooltip = d)
        }
        if (bMap.values.all { it.games.isEmpty() && it.failure != null }) {
            val d = Env.str(R.string.sports_desc_unavailable)
            return ItemState(icon = Sym.STAR, filled = false, text = null, desc = d, tooltip = d)
        }

        val teams = SportsRules.teams(item)
        val pinned = SportsRules.pinnedIds(item)
        val allGames = SportsRules.gamesFor(leagues, teams, bMap, pinned)
        val anyLive = allGames.any { it.state == GameState.LIVE }
        val shownGames = SportsRules.barGames(allGames, SportsRules.liveOnly(item), pinned)

        if (shownGames.isEmpty()) {
            val d = Env.str(if (SportsRules.liveOnly(item) && allGames.isNotEmpty()) R.string.sports_no_live_games else R.string.sports_no_games)
            return ItemState(icon = Sym.STAR, filled = false, text = null, active = false, desc = d, tooltip = d)
        }

        val turns = shownGames.map { g -> SportsRules.barText(g, formatStartTime(g.startEpochMs)) }
        val turnSec = SportsRules.turnSec(item)
        val idx = ((Now.wall() / 1000L) / turnSec).mod(turns.size)
        val current = shownGames[idx]
        val isPinnedLive = current.state == GameState.LIVE && current.id in pinned
        val icon = when {
            isPinnedLive -> Sym.PUSH_PIN
            current.state == GameState.LIVE -> Sym.PLAY_CIRCLE
            current.state == GameState.FINAL -> Sym.CHECK_CIRCLE
            current.state == GameState.UPCOMING -> Sym.SCHEDULE
            else -> Sym.STAR
        }
        val summary = shownGames.take(5).joinToString("; ") { g -> SportsRules.spokenGame(g, formatStartTime(g.startEpochMs)) }
        val desc = Env.str(R.string.sports_desc_live, summary)
        val tooltip = "${current.league.code}: ${SportsRules.spokenGame(current, formatStartTime(current.startEpochMs))}"
        return ItemState(
            icon = icon,
            filled = true,
            text = turns[idx],
            turns = if (turns.size > 1) turns else null,
            textEnd = true,
            tone = if (anyLive) Tone.ACCENT else Tone.NORMAL,
            active = anyLive,
            desc = desc,
            tooltip = tooltip,
            textLimit = SportsRules.BAR_CHARS,
        )
    }

    override fun forgetFetched() {
        stagedName = null
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> SportsMenu(item, host) }
    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set -> SportsOptions(item, set) }

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "staged=${stagedName ?: "none"} on=${Online.on(online)}"
        "stage" -> {
            val name = args.getOrNull(1).orEmpty()
            val sample = SportsRules.sample(name, League.NFL, Now.wall())
            if (sample == null) "sports stage live|final|upcoming|offline" else {
                stagedName = name
                Ticker.refresh(type)
                "staged $name"
            }
        }
        "off" -> {
            stagedName = null
            Ticker.refresh(type)
            "staging off"
        }
        else -> null
    }
}
