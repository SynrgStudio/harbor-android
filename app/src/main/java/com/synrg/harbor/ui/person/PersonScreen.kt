package com.synrg.harbor.ui.person

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.synrg.harbor.R
import com.synrg.harbor.core.AppGraph
import com.synrg.harbor.core.Strings
import com.synrg.harbor.data.Img
import com.synrg.harbor.data.MediaItem
import com.synrg.harbor.data.Person
import com.synrg.harbor.ui.components.MessageBlock
import com.synrg.harbor.ui.components.PosterCard
import com.synrg.harbor.ui.components.PrimaryButton
import com.synrg.harbor.ui.components.RoundIconButton
import com.synrg.harbor.ui.theme.HIcons
import com.synrg.harbor.ui.theme.HarborColors
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Immutable
data class PersonState(
    val person: Person,
    val knownFor: List<MediaItem> = emptyList(),
    val credits: List<MediaItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * With a TMDB key and the person's TMDB id: one request with everything. Without a key: the
 * addon's search gives a quick list with posters (at most 40 titles per type), and Wikidata the
 * full filmography and what the person is best known for; titles only Wikidata knew get their
 * poster from the addon once they scroll into view.
 */
class PersonViewModel(
    private val graph: AppGraph,
    private val name: String,
    private val personId: Int?,
    private val photo: String?,
    private val titleImdb: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(PersonState(Person(name, photo, null, null, null, null, null, emptyList(), emptyList())))
    val state: StateFlow<PersonState> = _state

    private val requested = HashSet<String>()
    private val gate = Semaphore(4)

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            if (personId != null && graph.meta.hasKey) {
                runCatching { graph.meta.person(personId) }
                    .onSuccess { p ->
                        _state.value = PersonState(p.copy(profilePath = p.profilePath ?: photo), p.knownFor, p.credits, loading = false)
                    }
                    .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: Strings.get(R.string.detail_no_connection)) } }
                return@launch
            }
            val works = async { titleImdb?.let { graph.meta.filmography(it, name) } }
            val quick = runCatching { graph.meta.personCredits(name) }.getOrDefault(emptyList())
            _state.update { it.copy(credits = newestFirst(quick)) }
            val wiki = works.await()
            val byKey = LinkedHashMap<String, MediaItem>()
            quick.forEach { byKey[it.key] = it }
            wiki.orEmpty().forEach { w ->
                val key = "${w.type}:${w.tmdbId}"
                if (key !in byKey) byKey[key] = MediaItem(w.tmdbId, w.type, w.title, year = w.year)
            }
            val known = wiki.orEmpty().sortedByDescending { it.fame }.mapNotNull { byKey["${it.type}:${it.tmdbId}"] }.take(12)
            _state.update {
                it.copy(
                    credits = newestFirst(byKey.values.toList()),
                    knownFor = known,
                    loading = false,
                    error = if (byKey.isEmpty() && quick.isEmpty() && wiki == null) Strings.get(R.string.person_error) else null,
                )
            }
            // Titles Wikidata only has an id for: look them up now (they stay hidden until they have a name).
            byKey.values.filter { it.title.isBlank() }.forEach(::resolve)
        }
    }

    /** A title only Wikidata knew: its poster and name in the app's language, once it is on screen. */
    fun resolve(item: MediaItem) {
        if (item.posterPath != null || !requested.add(item.key)) return
        viewModelScope.launch {
            val found = gate.withPermit { graph.meta.item(item.type, item.id) }
            if (found == null) {
                // No poster and no name to show: nothing to tap on.
                if (item.title.isBlank()) {
                    _state.update { s -> s.copy(credits = s.credits.filter { it.key != item.key }, knownFor = s.knownFor.filter { it.key != item.key }) }
                }
                return@launch
            }
            val merged = found.copy(year = found.year ?: item.year)
            _state.update { s ->
                s.copy(
                    credits = s.credits.map { if (it.key == item.key) merged else it },
                    knownFor = s.knownFor.map { if (it.key == item.key) merged else it },
                )
            }
        }
    }

    private val MediaItem.key: String get() = "$type:$id"

    private fun newestFirst(items: List<MediaItem>) = items.sortedWith(compareByDescending(nullsFirst()) { it.year })
        .let { list -> list.filter { it.year != null } + list.filter { it.year == null } }
}

@Composable
fun PersonScreen(vm: PersonViewModel, onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(HarborColors.Bg)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") { Header(state.person) }
            if (state.knownFor.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "known") {
                    Column(Modifier.padding(top = 8.dp)) {
                        GridTitle(stringResource(R.string.person_known_for))
                        Spacer(Modifier.height(10.dp))
                        // Edge to edge: the row scrolls under the grid's side padding.
                        LazyRow(
                            Modifier.bleed(16.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.knownFor.filter { it.title.isNotBlank() }, key = { "${it.type}:${it.id}" }) { item ->
                                Poster(item, vm, onOpen, 112.dp)
                            }
                        }
                    }
                }
            }
            if (state.credits.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "filmography") {
                    GridTitle(stringResource(R.string.person_filmography), Modifier.padding(top = 14.dp))
                }
                items(state.credits.filter { it.title.isNotBlank() }, key = { "c-${it.type}:${it.id}" }, contentType = { "poster" }) { item ->
                    Poster(item, vm, onOpen, Dp.Unspecified)
                }
            }
            when {
                state.loading -> item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
                state.error != null -> item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                    MessageBlock(
                        stringResource(R.string.person_error), state.error.orEmpty(),
                        action = { PrimaryButton(stringResource(R.string.common_retry), HIcons.Refresh, vm::load) },
                    )
                }
                state.credits.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Text(
                        stringResource(R.string.person_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
        }
        RoundIconButton(
            HIcons.Back, stringResource(R.string.detail_back), onBack,
            modifier = Modifier.statusBarsPadding().padding(start = 12.dp, top = 6.dp),
        )
    }
}

@Composable
private fun GridTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = HarborColors.Text, modifier = modifier)
}

/** Widens a child by [by] on each side, past its parent's padding. */
private fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun Poster(item: MediaItem, vm: PersonViewModel, onOpen: (MediaItem) -> Unit, width: Dp) {
    LaunchedEffect(item.type, item.id, item.posterPath) { vm.resolve(item) }
    Column(if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier) {
        PosterCard(item, onClick = { onOpen(item) }, width = width, modifier = if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier)
        item.year?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = HarborColors.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }
    }
}

@Composable
private fun Header(person: Person) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(132.dp).clip(CircleShape).background(HarborColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            if (person.profilePath != null) {
                AsyncImage(Img.profile(person.profilePath, "h632"), person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(HIcons.Person, null, tint = HarborColors.TextFaint, modifier = Modifier.size(56.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(person.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        val department = when (person.department) {
            "Acting" -> stringResource(R.string.person_dept_acting)
            "Directing" -> stringResource(R.string.person_dept_directing)
            "Writing" -> stringResource(R.string.person_dept_writing)
            "Production" -> stringResource(R.string.person_dept_production)
            else -> person.department
        }
        val born = person.birthday?.let { formatDate(it, locale) }
        val deathday = person.deathday
        val birthplace = person.birthplace
        val life = when {
            born != null && deathday != null -> stringResource(R.string.person_lived, born, formatDate(deathday, locale))
            born != null && birthplace != null -> stringResource(R.string.person_born_in, born, birthplace)
            born != null -> stringResource(R.string.person_born, born)
            else -> null
        }
        listOfNotNull(department, life).takeIf { it.isNotEmpty() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = HarborColors.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        person.biography?.let { bio ->
            Spacer(Modifier.height(12.dp))
            Text(
                bio,
                style = MaterialTheme.typography.bodyMedium,
                color = HarborColors.TextDim,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().animateContentSize()
                    .clickable(remember { MutableInteractionSource() }, null) { expanded = !expanded },
            )
        }
    }
}

private fun formatDate(iso: String, locale: Locale): String =
    runCatching { LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)) }
        .getOrDefault(iso)
