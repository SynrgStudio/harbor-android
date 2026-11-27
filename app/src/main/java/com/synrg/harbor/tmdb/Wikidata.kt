package com.synrg.harbor.tmdb

import com.synrg.harbor.BuildConfig
import com.synrg.harbor.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.synrg.harbor.data.MediaType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Sagas without a TMDB key: Wikidata knows which film series a movie is part of ("part of the
 * series") and every film's TMDB id, with no key and no account. A movie can be in several series
 * (a trilogy and a wider franchise); the smallest one is the saga.
 */
class Wikidata(private val http: OkHttpClient, private val json: Json) {
    /** A saga's name in [language] (ISO 639-1) and its movies as TMDB ids, in release order. */
    class SagaRef(val name: String, val tmdbIds: List<Int>)

    suspend fun saga(imdbId: String, language: String): SagaRef? {
        if (!IMDB.matches(imdbId)) return null
        val query = """
            SELECT ?series ?label ?labelEn ?labelMul ?tmdb ?date ?ord WHERE {
              ?film wdt:P345 "$imdbId"; wdt:P179 ?series.
              ?item wdt:P179 ?series; wdt:P4947 ?tmdb.
              OPTIONAL { ?item p:P179 ?st. ?st ps:P179 ?series; pq:P1545 ?ord. }
              OPTIONAL { ?item wdt:P577 ?date. }
              OPTIONAL { ?series rdfs:label ?label. FILTER(LANG(?label) = "$language") }
              OPTIONAL { ?series rdfs:label ?labelEn. FILTER(LANG(?labelEn) = "en") }
              OPTIONAL { ?series rdfs:label ?labelMul. FILTER(LANG(?labelMul) = "mul") }
            }
        """.trimIndent()
        val rows = run(query) ?: return null

        class Part(val tmdb: Int, var date: String?, var ord: Int?)
        val bySeries = rows.groupBy { it["series"]?.value ?: "" }.filterKeys { it.isNotEmpty() }
        val chosen = bySeries.mapValues { (_, list) -> list.mapNotNull { it["tmdb"]?.value?.toIntOrNull() }.toSet().size }
            .filterValues { it in 2..MAX_PARTS }
            .minByOrNull { it.value }?.key ?: return null
        val list = bySeries.getValue(chosen)
        val name = list.label() ?: return null
        val parts = LinkedHashMap<Int, Part>()
        for (row in list) {
            val tmdb = row["tmdb"]?.value?.toIntOrNull() ?: continue
            val date = row["date"]?.value?.take(10)
            val ord = row["ord"]?.value?.toIntOrNull()
            val part = parts.getOrPut(tmdb) { Part(tmdb, date, ord) }
            // Several release dates (one per country): the first one counts.
            if (date != null && (part.date == null || date < part.date!!)) part.date = date
            if (ord != null && (part.ord == null || ord < part.ord!!)) part.ord = ord
        }
        val ordered = parts.values.sortedWith(compareBy<Part, String?>(nullsLast()) { it.date }.thenBy(nullsLast()) { it.ord })
        return SagaRef(name, ordered.map { it.tmdb })
    }

    /**
     * A title a person worked on, from Wikidata: its TMDB id, its name (blank when Wikidata has
     * none in the language or in English; the addon fills it in), and how known it is.
     */
    class Work(val type: MediaType, val tmdbId: Int, val title: String, val year: String?, val fame: Int)

    /**
     * Everything [name] was in, found through the cast of the title the user came from
     * ([titleImdb]), so two people with the same name aren't mixed up. [Work.fame] is the number
     * of Wikipedia articles about the title: a good measure of what someone is known for.
     * Labels are read directly: many well-known titles only have a "mul" label now, which the
     * label service skipped.
     */
    suspend fun filmography(titleImdb: String, name: String, language: String): List<Work>? {
        if (!IMDB.matches(titleImdb)) return null
        val quoted = name.replace("\\", "\\\\").replace("\"", "\\\"")
        val query = """
            SELECT ?work ?movie ?tv ?date ?links ?label ?labelEn ?labelMul WHERE {
              ?title wdt:P345 "$titleImdb"; wdt:P161 ?person.
              FILTER EXISTS { ?person rdfs:label ?name. FILTER(STR(?name) = "$quoted") }
              ?work wdt:P161 ?person; wikibase:sitelinks ?links.
              OPTIONAL { ?work wdt:P4947 ?movie. }
              OPTIONAL { ?work wdt:P4983 ?tv. }
              FILTER(BOUND(?movie) || BOUND(?tv))
              OPTIONAL { ?work wdt:P577 ?date. }
              OPTIONAL { ?work rdfs:label ?label. FILTER(LANG(?label) = "$language") }
              OPTIONAL { ?work rdfs:label ?labelEn. FILTER(LANG(?labelEn) = "en") }
              OPTIONAL { ?work rdfs:label ?labelMul. FILTER(LANG(?labelMul) = "mul") }
            }
        """.trimIndent()
        val rows = run(query) ?: return null
        return rows.groupBy { it["work"]?.value }.mapNotNull { (work, list) ->
            work ?: return@mapNotNull null
            val first = list.first()
            val movie = first["movie"]?.value?.toIntOrNull()
            val tv = first["tv"]?.value?.toIntOrNull()
            Work(
                type = if (movie != null) MediaType.Movie else MediaType.Tv,
                tmdbId = movie ?: tv ?: return@mapNotNull null,
                title = list.label() ?: "",
                year = list.mapNotNull { it["date"]?.value?.take(4) }.minOrNull(),
                fame = first["links"]?.value?.toIntOrNull() ?: 0,
            )
        }.distinctBy { "${it.type}:${it.tmdbId}" }
    }

    /** The name in the language, else in English, else the "mul" label (the same in every language). */
    private fun List<Map<String, SparqlValue>>.label(): String? =
        firstNotNullOfOrNull { it["label"]?.value } ?: firstNotNullOfOrNull { it["labelEn"]?.value }
            ?: firstNotNullOfOrNull { it["labelMul"]?.value }

    private suspend fun run(query: String): List<Map<String, SparqlValue>>? {
        val url = ENDPOINT.toHttpUrl().newBuilder().addQueryParameter("query", query).build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/sparql-results+json")
            // Wikidata asks every client to say what it is.
            .header("User-Agent", "Harbor/${BuildConfig.VERSION_NAME} (https://github.com/SynrgStudio/harbor-android)")
            .build()
        return http.newCall(request).await().use { response ->
            if (!response.isSuccessful) return null
            withContext(Dispatchers.Default) { json.decodeFromString(SparqlResponse.serializer(), response.body.string()) }
        }.results.bindings
    }

    private companion object {
        const val ENDPOINT = "https://query.wikidata.org/sparql"
        /** A shared universe with dozens of films is not a saga anyone scrolls through. */
        const val MAX_PARTS = 40
        val IMDB = Regex("""tt\d{5,10}""")
    }
}

@Serializable
private class SparqlResponse(val results: SparqlResults = SparqlResults())

@Serializable
private class SparqlResults(val bindings: List<Map<String, SparqlValue>> = emptyList())

@Serializable
private class SparqlValue(val value: String = "")
