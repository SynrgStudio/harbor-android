package com.synrg.harbor.player

import java.util.Locale

/** Stremio subtitle addons use ISO 639-2 ("spa", "eng", "pob"); the player wants ISO 639-1. */
object Languages {
    private val map = mapOf(
        "spa" to "es", "esp" to "es", "spl" to "es", "spn" to "es", "eng" to "en", "por" to "pt", "pob" to "pt", "pt-br" to "pt",
        "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "ita" to "it", "jpn" to "ja", "kor" to "ko",
        "chi" to "zh", "zho" to "zh", "rus" to "ru", "ara" to "ar", "tur" to "tr", "pol" to "pl", "dut" to "nl",
        "nld" to "nl", "swe" to "sv", "nor" to "no", "dan" to "da", "fin" to "fi", "gre" to "el", "ell" to "el",
        "heb" to "he", "hin" to "hi", "hun" to "hu", "cze" to "cs", "ces" to "cs", "rum" to "ro", "ron" to "ro",
        "ukr" to "uk", "vie" to "vi", "tha" to "th", "ind" to "id", "may" to "ms", "msa" to "ms", "cat" to "ca",
    )

    fun toIso2(lang: String?): String? {
        val l = lang?.lowercase(Locale.ROOT)?.trim() ?: return null
        return map[l] ?: l.take(2)
    }

    fun matches(lang: String?, iso2: String): Boolean = iso2.isNotEmpty() && toIso2(lang) == iso2

    fun label(lang: String?): String {
        val code = toIso2(lang) ?: return "Desconocido"
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag("es"))
        val nice = if (name.isNullOrBlank() || name == code) lang ?: code else name
        val suffix = if (lang.equals("pob", true) || lang.equals("pt-br", true)) " (Brasil)" else ""
        return nice.replaceFirstChar { it.titlecase(Locale.forLanguageTag("es")) } + suffix
    }
}
