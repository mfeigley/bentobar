package com.meridian11.books.bentobar.items

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.SportsRules.Game
import com.meridian11.books.bentobar.items.SportsRules.GameState
import com.meridian11.books.bentobar.items.SportsRules.League
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.SearchField
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import java.time.ZoneId

// Menu and settings for the Sports scores item. No logging.

private const val ESPN_SITE = "https://www.espn.com/"

@Composable
internal fun SportsMenu(item: ItemConfig, host: MenuHost) {
    rememberTick()
    val online by Online.state.collectAsState()
    val on = Online.Service.ESPN in online.on
    val leagues = remember(item) { SportsRules.leagues(item) }
    val teams = remember(item) { SportsRules.teams(item) }
    val pinnedIds = remember(item) { SportsRules.pinnedIds(item) }
    val title = stringResource(R.string.item_sports_title)
    LaunchedEffect(item.id, on) { SportsItem.opened(item) }

    val boards = SportsItem.boardsFor(item, leagues, ask = false)
    val subtitle = remember(leagues, teams) {
        val lText = leagues.joinToString(" · ") { it.code }
        if (teams.isEmpty()) lText else "$lText (${teams.joinToString(", ")})"
    }

    when {
        !on && !SportsItem.isStaged(item) -> MenuCard(
            Sym.STAR,
            title,
            stringResource(if (Online.setUp(Online.Service.ESPN)) R.string.common_off_in_setup else R.string.common_not_set_up),
        ) {
            MenuNote(stringResource(R.string.sports_consent))
            MenuEntry(Sym.TOGGLE_ON, stringResource(R.string.sports_turn_on)) {
                Online.turnOn(Online.Service.ESPN)
                Ticker.refresh(SportsItem.type)
            }
        }
        leagues.isEmpty() -> MenuCard(Sym.STAR, title, stringResource(R.string.sports_none_yet)) {
            MenuEntry(Sym.ADD, stringResource(R.string.sports_choose_leagues)) { host.openItemSettings(item.id) }
        }
        boards.isEmpty() || boards.values.all { it.fetchedAt == 0L && it.failure == null } -> MenuCard(
            Sym.STAR,
            title,
            stringResource(R.string.usage_loading),
        ) {
            MenuEntry(Sym.EDIT, stringResource(R.string.sports_edit)) { host.openItemSettings(item.id) }
        }
        boards.values.all { it.games.isEmpty() && it.failure != null } -> {
            val offline = boards.values.any { it.failure == Failure.OFFLINE }
            MenuCard(Sym.STAR, title, stringResource(if (offline) R.string.common_no_connection else R.string.sports_desc_unavailable)) {
                MenuEntry(Sym.REFRESH, stringResource(R.string.common_try_again)) { SportsItem.refresh(item) }
                MenuEntry(Sym.EDIT, stringResource(R.string.sports_edit)) { host.openItemSettings(item.id) }
            }
        }
        else -> {
            val now = Now.wall()
            val games = SportsRules.gamesFor(leagues, teams, boards, pinnedIds, now)
            val liveGameIds = remember(games, now) { games.filter { it.inLiveRotation(now) }.mapTo(HashSet()) { it.id } }
            val anyPinnedLive = games.any { it.inLiveRotation(now) && it.id in pinnedIds }
            val showLeagueBadge = leagues.size > 1
            MenuCard(Sym.STAR, title, subtitle.ifEmpty { null }) {
                if (games.isEmpty()) {
                    MenuNote(stringResource(R.string.sports_no_games))
                } else {
                    games.take(12).forEach { g ->
                        val inRotation = g.inLiveRotation(now)
                        val isPinned = inRotation && g.id in pinnedIds
                        GameRow(
                            g = g,
                            showLeague = showLeagueBadge,
                            isPinned = isPinned,
                            onTogglePin = if (inRotation) {
                                {
                                    Store.updateItem(item.id) { SportsRules.togglePinned(it, g.id, liveGameIds) }
                                    Ticker.refresh(SportsItem.type)
                                }
                            } else null,
                            onClick = {
                                host.close()
                                Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(SportsRules.gamePage(g))))
                            },
                        )
                    }
                }
                val newest = boards.values.filter { it.fetchedAt > 0 }.maxByOrNull { it.fetchedAt }
                if (newest != null) {
                    val clock = WeatherItem.times().clock(newest.fetchedAt, ZoneId.systemDefault())
                    val anyFailure = boards.values.mapNotNull { it.failure }.firstOrNull()
                    val updated = when (anyFailure) {
                        Failure.OFFLINE -> stringResource(R.string.common_updated_offline, clock)
                        null -> stringResource(R.string.common_updated, clock)
                        else -> stringResource(R.string.common_updated_no_answer, clock)
                    }
                    MenuNote(stringResource(R.string.sports_note, updated))
                }
                MenuDivider()
                if (anyPinnedLive) {
                    MenuEntry(Sym.PUSH_PIN, stringResource(R.string.sports_unpin_all)) {
                        Store.updateItem(item.id) { SportsRules.clearPinned(it) }
                        Ticker.refresh(SportsItem.type)
                    }
                }
                MenuEntry(Sym.REFRESH, stringResource(R.string.common_refresh)) { SportsItem.refresh(item) }
                MenuEntry(Sym.EDIT, stringResource(R.string.sports_edit)) { host.openItemSettings(item.id) }
                val site = stringResource(R.string.sports_site)
                val opens = stringResource(R.string.common_opens_browser, site)
                MenuEntry(Sym.OPEN_IN_NEW, site, modifier = Modifier.semantics { contentDescription = opens }) {
                    host.close()
                    Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(ESPN_SITE)))
                }
            }
        }
    }
}

@Composable
private fun GameRow(
    g: Game,
    showLeague: Boolean,
    isPinned: Boolean,
    onTogglePin: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val startTime = if (g.state == GameState.UPCOMING) SportsItem.formatStartTime(g.startEpochMs) else null
    val spoken = SportsRules.spokenGame(g, startTime)
    val opensBrowser = stringResource(R.string.common_opens_browser, "${g.league.code}: $spoken")
    val awayWon = g.state == GameState.FINAL && g.awayScore != null && g.homeScore != null && g.awayScore > g.homeScore
    val homeWon = g.state == GameState.FINAL && g.awayScore != null && g.homeScore != null && g.homeScore > g.awayScore

    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered || focused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f) else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .semantics {
                contentDescription = opensBrowser
                role = Role.Button
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val header = if (showLeague) "${g.league.code} · ${startTime ?: g.statusDetail}" else (startTime ?: g.statusDetail)
            Text(
                header,
                style = MaterialTheme.typography.labelSmall,
                color = if (g.state == GameState.LIVE) MaterialTheme.colorScheme.primary else quiet,
                fontWeight = if (g.state == GameState.LIVE) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (g.situationText != null) {
                Text(
                    g.situationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = quiet,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
            }
            if (onTogglePin != null) {
                val matchup = "${g.awayAbbr} vs ${g.homeAbbr}"
                val pinLabel = stringResource(if (isPinned) R.string.sports_unpin_game else R.string.sports_pin_game, matchup)
                Box(
                    Modifier.size(24.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onTogglePin)
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) {
                    SymIcon(
                        Sym.PUSH_PIN,
                        size = 15.sp,
                        filled = isPinned,
                        contentDescription = pinLabel,
                        color = if (isPinned) MaterialTheme.colorScheme.primary else quiet,
                    )
                }
                Spacer(Modifier.width(2.dp))
            }
            SymIcon(
                Sym.OPEN_IN_NEW,
                size = 13.sp,
                color = if (hovered || focused) MaterialTheme.colorScheme.onSurface else quiet.copy(alpha = 0.7f),
            )
        }
        TeamScoreLine(
            abbr = g.awayAbbr,
            name = g.awayName,
            rank = g.awayRank,
            score = g.awayScore,
            hasPossession = g.possessionAbbr == g.awayAbbr,
            emphasized = g.state == GameState.LIVE || awayWon,
            dimmed = homeWon,
        )
        TeamScoreLine(
            abbr = g.homeAbbr,
            name = g.homeName,
            rank = g.homeRank,
            score = g.homeScore,
            hasPossession = g.possessionAbbr == g.homeAbbr,
            emphasized = g.state == GameState.LIVE || homeWon,
            dimmed = awayWon,
        )
    }
}

@Composable
private fun TeamScoreLine(
    abbr: String,
    name: String,
    rank: Int?,
    score: Int?,
    hasPossession: Boolean,
    emphasized: Boolean,
    dimmed: Boolean,
) {
    val textColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val weight = if (emphasized && !dimmed) FontWeight.SemiBold else FontWeight.Normal
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val rankPrefix = if (rank != null) "#$rank " else ""
        val possSuffix = if (hasPossession) " •" else ""
        val display = if (name != abbr) "$rankPrefix$abbr · $name$possSuffix" else "$rankPrefix$abbr$possSuffix"
        Text(
            display,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontWeight = weight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (score != null) {
            Text(
                score.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = textColor,
                fontWeight = weight,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SportsOptions(item: ItemConfig, set: (ItemConfig) -> Unit) {
    rememberTick()
    val online by Online.state.collectAsState()
    val on = Online.Service.ESPN in online.on
    key(item.id) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(stringResource(R.string.sports_consent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!on) {
                Spacer(Modifier.height(4.dp))
                MenuEntry(Sym.TOGGLE_ON, stringResource(R.string.sports_turn_on)) {
                    Online.turnOn(Online.Service.ESPN)
                    Ticker.refresh(SportsItem.type)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.sports_leagues_section), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.sports_leagues_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val chosenLeagues = remember(item) { SportsRules.leagues(item) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                League.entries.forEach { league ->
                    val selected = league in chosenLeagues
                    val enabled = selected || chosenLeagues.size < SportsRules.MOST_LEAGUES
                    FilterChip(
                        selected = selected,
                        enabled = enabled,
                        onClick = {
                            Online.turnOn(Online.Service.ESPN)
                            set(SportsRules.toggleLeague(item, league))
                        },
                        label = { Text(league.code) },
                        leadingIcon = if (selected) { { SymIcon(Sym.CHECK) } } else null,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.sports_teams_section), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.sports_teams_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val teams = remember(item) { SportsRules.teams(item) }
            if (teams.size < SportsRules.MOST_TEAMS) {
                SearchField(
                    label = stringResource(R.string.sports_team_add_label),
                    placeholder = "CHI",
                    submit = stringResource(R.string.sports_team_add_button),
                    onChange = {},
                    onEnter = { text ->
                        if (SportsRules.parseTeamToken(text) != null) {
                            Online.turnOn(Online.Service.ESPN)
                            set(SportsRules.addTeam(item, text))
                        }
                    },
                )
            } else {
                MenuNote(stringResource(R.string.sports_teams_full))
            }
            if (teams.isEmpty()) {
                MenuNote(stringResource(R.string.sports_teams_all))
            } else {
                teams.forEach { t ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        val remove = stringResource(R.string.sports_remove_team, t)
                        IconButton(onClick = { set(SportsRules.removeTeam(item, t)) }, modifier = Modifier.semantics { contentDescription = remove }) {
                            SymIcon(Sym.CLOSE)
                        }
                    }
                }
            }
        }
    }
    SwitchRow(
        stringResource(R.string.sports_live_only),
        SportsRules.liveOnly(item),
        help = stringResource(R.string.sports_live_only_help),
    ) { onLive -> set(SportsRules.withLiveOnly(item, onLive)) }

    val res = LocalContext.current.resources
    ChoiceRow(
        stringResource(R.string.sports_turn_label),
        SportsRules.TURNS.map { sec ->
            sec to if (sec % 60 == 0) res.getQuantityString(R.plurals.common_minutes_short, sec / 60, sec / 60)
            else res.getQuantityString(R.plurals.stocks_seconds_short, sec, sec)
        },
        SportsRules.turnSec(item),
        help = stringResource(R.string.sports_turn_help),
    ) { set(item.with("turnSec", it.takeIf { s -> s != SportsRules.TURN_SEC }?.toString())) }
}
