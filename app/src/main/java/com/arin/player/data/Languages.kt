package com.arin.player.data

/**
 * A spoken language the user can pick for a video.
 *
 * [whisper] = Whisper's ISO-639-1 code when Whisper (Groq/OpenAI/Cloudflare/offline) knows it.
 * When it's null, speech is recognised by Meta's Omnilingual model (1,600+ languages, on the phone).
 * [experimental] = no speech model has been trained on this language yet: best effort only.
 */
data class SpokenLanguage(
    val code: String,
    val name: String,
    val whisper: String? = null,
    val region: String = "",
    val experimental: Boolean = false,
) {
    val usesWorldModel get() = whisper == null
}

object Languages {
    val AUTO = SpokenLanguage("auto", "Auto-detect")

    /** Languages Whisper knows (fast cloud recognition with word timing). */
    private val whisper = listOf(
        "en" to "English", "hi" to "Hindi", "bn" to "Bengali", "as" to "Assamese", "mr" to "Marathi",
        "ta" to "Tamil", "te" to "Telugu", "kn" to "Kannada", "ml" to "Malayalam", "gu" to "Gujarati",
        "pa" to "Punjabi", "ur" to "Urdu", "ne" to "Nepali", "sd" to "Sindhi", "sa" to "Sanskrit",
        "si" to "Sinhala", "my" to "Burmese", "bo" to "Tibetan", "zh" to "Chinese", "yue" to "Cantonese",
        "ja" to "Japanese", "ko" to "Korean", "th" to "Thai", "lo" to "Lao", "km" to "Khmer",
        "vi" to "Vietnamese", "id" to "Indonesian", "ms" to "Malay", "tl" to "Tagalog", "jw" to "Javanese",
        "su" to "Sundanese", "ar" to "Arabic", "fa" to "Persian", "ps" to "Pashto", "he" to "Hebrew",
        "tr" to "Turkish", "az" to "Azerbaijani", "uz" to "Uzbek", "kk" to "Kazakh", "tg" to "Tajik",
        "tk" to "Turkmen", "tt" to "Tatar", "ba" to "Bashkir", "mn" to "Mongolian", "ka" to "Georgian",
        "hy" to "Armenian", "ru" to "Russian", "uk" to "Ukrainian", "be" to "Belarusian", "pl" to "Polish",
        "cs" to "Czech", "sk" to "Slovak", "sl" to "Slovenian", "hr" to "Croatian", "sr" to "Serbian",
        "bs" to "Bosnian", "mk" to "Macedonian", "bg" to "Bulgarian", "ro" to "Romanian", "hu" to "Hungarian",
        "el" to "Greek", "sq" to "Albanian", "lt" to "Lithuanian", "lv" to "Latvian", "et" to "Estonian",
        "fi" to "Finnish", "sv" to "Swedish", "no" to "Norwegian", "nn" to "Norwegian Nynorsk", "da" to "Danish",
        "is" to "Icelandic", "fo" to "Faroese", "de" to "German", "nl" to "Dutch", "lb" to "Luxembourgish",
        "fr" to "French", "oc" to "Occitan", "br" to "Breton", "es" to "Spanish", "ca" to "Catalan",
        "gl" to "Galician", "eu" to "Basque", "pt" to "Portuguese", "it" to "Italian", "la" to "Latin",
        "mt" to "Maltese", "cy" to "Welsh", "sw" to "Swahili", "am" to "Amharic", "so" to "Somali",
        "yo" to "Yoruba", "ha" to "Hausa", "sn" to "Shona", "ln" to "Lingala", "mg" to "Malagasy",
        "af" to "Afrikaans", "ht" to "Haitian Creole", "mi" to "Maori", "haw" to "Hawaiian", "yi" to "Yiddish",
    ).map { (c, n) -> SpokenLanguage(c.uppercase(), n, whisper = c) }

    /** Indian & regional languages recognised by Meta's Omnilingual model (on the phone). */
    private val world = listOf(
        // North-East India
        SpokenLanguage("NAG", "Nagamese (Naga Pidgin)", region = "Nagaland"),
        SpokenLanguage("LUS", "Mizo", region = "Mizoram"),
        SpokenLanguage("KHA", "Khasi", region = "Meghalaya"),
        SpokenLanguage("GRT", "Garo", region = "Meghalaya"),
        SpokenLanguage("BRX", "Bodo", region = "Assam"),
        SpokenLanguage("MNI", "Manipuri (Meitei)", region = "Manipur"),
        SpokenLanguage("TRP", "Kokborok", region = "Tripura"),
        SpokenLanguage("RAH", "Rabha", region = "Assam"),
        SpokenLanguage("TCZ", "Thadou Kuki", region = "Manipur"),
        SpokenLanguage("TGJ", "Tagin", region = "Arunachal Pradesh"),
        SpokenLanguage("NST", "Tangsa", region = "Arunachal Pradesh"),
        SpokenLanguage("MHU", "Digaro Mishmi", region = "Arunachal Pradesh"),
        SpokenLanguage("KIX", "Khiamniungan Naga", region = "Nagaland"),
        SpokenLanguage("KAC", "Singpho / Jingpho", region = "Arunachal Pradesh"),
        SpokenLanguage("LIS", "Lisu", region = "Arunachal Pradesh"),
        SpokenLanguage("SIP", "Sikkimese (Bhutia)", region = "Sikkim"),
        SpokenLanguage("LIF", "Limbu", region = "Sikkim"),
        SpokenLanguage("NEW", "Newari", region = "Sikkim"),
        SpokenLanguage("SYL", "Sylheti", region = "Assam / Tripura"),
        SpokenLanguage("CNH", "Hakha Chin", region = "Mizoram"),
        SpokenLanguage("CTD", "Tedim Chin (Zomi)", region = "Manipur"),
        // Rest of India
        SpokenLanguage("ORY", "Odia", region = "Odisha"),
        SpokenLanguage("BHO", "Bhojpuri", region = "Bihar / UP"),
        SpokenLanguage("MAI", "Maithili", region = "Bihar"),
        SpokenLanguage("MAG", "Magahi", region = "Bihar"),
        SpokenLanguage("AWA", "Awadhi", region = "Uttar Pradesh"),
        SpokenLanguage("BFY", "Bagheli", region = "Madhya Pradesh"),
        SpokenLanguage("HNE", "Chhattisgarhi", region = "Chhattisgarh"),
        SpokenLanguage("BGC", "Haryanvi", region = "Haryana"),
        SpokenLanguage("GBM", "Garhwali", region = "Uttarakhand"),
        SpokenLanguage("XNR", "Kangri", region = "Himachal Pradesh"),
        SpokenLanguage("DGO", "Dogri", region = "Jammu"),
        SpokenLanguage("KAS", "Kashmiri", region = "Kashmir"),
        SpokenLanguage("GOM", "Konkani", region = "Goa"),
        SpokenLanguage("TCY", "Tulu", region = "Karnataka"),
        SpokenLanguage("KRU", "Kurukh", region = "Jharkhand"),
        SpokenLanguage("HOC", "Ho", region = "Jharkhand"),
        SpokenLanguage("SCK", "Sadri / Nagpuri", region = "Jharkhand"),
        SpokenLanguage("RKT", "Rangpuri / Kamtapuri", region = "West Bengal"),
        SpokenLanguage("CTG", "Chittagonian", region = "Bangladesh"),
    )

    /**
     * No speech-recognition model has been trained on these yet. The world model still writes down
     * what it hears and the AI translator tries its best — expect rough subtitles.
     */
    private val experimental = listOf(
        SpokenLanguage("MJW", "Karbi", region = "Karbi Anglong, Assam", experimental = true),
        SpokenLanguage("AJZ", "Amri Karbi", region = "Assam", experimental = true),
        SpokenLanguage("MRG", "Mising", region = "Assam", experimental = true),
        SpokenLanguage("DIS", "Dimasa", region = "Assam", experimental = true),
        SpokenLanguage("LAX", "Tiwa", region = "Assam", experimental = true),
        SpokenLanguage("NJZ", "Nyishi", region = "Arunachal Pradesh", experimental = true),
        SpokenLanguage("ADI", "Adi", region = "Arunachal Pradesh", experimental = true),
        SpokenLanguage("APT", "Apatani", region = "Arunachal Pradesh", experimental = true),
        SpokenLanguage("ADL", "Galo", region = "Arunachal Pradesh", experimental = true),
        SpokenLanguage("TSJ", "Monpa (Tshangla)", region = "Arunachal Pradesh", experimental = true),
        SpokenLanguage("NJB", "Nocte", region = "Arunachal Pradesh", experimental = true),
        SpokenLanguage("NNP", "Wancho", region = "Arunachal Pradesh", experimental = true),
        SpokenLanguage("NJO", "Ao Naga", region = "Nagaland", experimental = true),
        SpokenLanguage("NJM", "Angami Naga", region = "Nagaland", experimental = true),
        SpokenLanguage("NSM", "Sumi Naga", region = "Nagaland", experimental = true),
        SpokenLanguage("NJH", "Lotha Naga", region = "Nagaland", experimental = true),
        SpokenLanguage("NBE", "Konyak Naga", region = "Nagaland", experimental = true),
        SpokenLanguage("NMF", "Tangkhul Naga", region = "Manipur", experimental = true),
        SpokenLanguage("HMR", "Hmar", region = "Manipur / Mizoram", experimental = true),
        SpokenLanguage("PCK", "Paite", region = "Manipur", experimental = true),
        SpokenLanguage("CCP", "Chakma", region = "Mizoram / Tripura", experimental = true),
        SpokenLanguage("LEP", "Lepcha", region = "Sikkim", experimental = true),
        SpokenLanguage("SAT", "Santali", region = "Jharkhand", experimental = true),
        SpokenLanguage("UNR", "Mundari", region = "Jharkhand", experimental = true),
        SpokenLanguage("GON", "Gondi", region = "Central India", experimental = true),
        SpokenLanguage("LMN", "Lambadi", region = "Telangana", experimental = true),
        SpokenLanguage("OTHER", "Other / not listed", experimental = true),
    )

    /** Everything, Indian languages first. */
    val all: List<SpokenLanguage> = run {
        val indian = setOf("HI", "BN", "AS", "MR", "TA", "TE", "KN", "ML", "GU", "PA", "UR", "NE", "SD", "SA", "EN")
        val (ind, rest) = whisper.partition { it.code in indian }
        listOf(AUTO) + ind + world + experimental + rest.sortedBy { it.name }
    }

    fun byCode(code: String?): SpokenLanguage = all.firstOrNull { it.code.equals(code, true) } ?: AUTO

    /** Display name for a detected code such as "KO" or "KHA". */
    fun nameOf(code: String): String = all.firstOrNull { it.code.equals(code, true) }?.name ?: code
}
