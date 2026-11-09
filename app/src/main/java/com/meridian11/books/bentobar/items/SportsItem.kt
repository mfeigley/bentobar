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
        every = { _, b -> SportsRules.every(b) },
        load = { league, last -> SportsLoad.load(league, last, Now.wall()) },
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

    internal fun opened(item: ItemConfig) {
        if (isStaged(item) || item.section == Section.OFF || !Online.on(online)) return
        for (l in SportsRules.leagues(item)) {
            val b = boards.peek(l)
            if (b == null) boards.want(l) else if (b.failure == null) boards.refresh(l, floorMs = SportsRules.MENU_MS)
        }
    }

    internal fun refresh(item: ItemConfig) {
        if (stagedName != null || !Online.on(online)) return
        for (l in SportsRules.leagues(item)) {
            boards.refresh(l, floorMs = 10_000L)
        }
    }

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
        val allGames = SportsRules.gamesFor(leagues, teams, bMap)
        val anyLive = allGames.any { it.state == GameState.LIVE }
        val shownGames = SportsRules.barGames(allGames, SportsRules.liveOnly(item))

        if (shownGames.isEmpty()) {
            val d = Env.str(if (SportsRules.liveOnly(item) && allGames.isNotEmpty()) R.string.sports_no_live_games else R.string.sports_no_games)
            return ItemState(icon = Sym.STAR, filled = false, text = null, active = false, desc = d, tooltip = d)
        }

        val turns = shownGames.map { g -> SportsRules.barText(g, formatStartTime(g.startEpochMs)) }
        val turnSec = SportsRules.turnSec(item)
        val idx = ((Now.wall() / 1000L) / turnSec).mod(turns.size)
        val current = shownGames[idx]
        val icon = when (current.state) {
            GameState.LIVE -> Sym.PLAY_CIRCLE
            GameState.FINAL -> Sym.CHECK_CIRCLE
            GameState.UPCOMING -> Sym.SCHEDULE
        }
        val summary = shownGames.take(5).joinToString("; ") { g -> SportsRules.spokenGame(g, formatStartTime(g.startEpochMs)) }
        val desc = Env.str(R.string.sports_desc_live, summary)
        val tooltip = "${current.league.code}: ${SportsRules.spokenGame(current, formatStartTime(current.startEpochMs))}"
        return ItemState(
            icon = icon,
            filled = true,
            text = turns[idx],
            turns = if (turns.size > 1) turns else null,
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
