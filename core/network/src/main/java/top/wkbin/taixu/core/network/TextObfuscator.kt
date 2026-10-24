package top.wkbin.taixu.core.network

import java.io.File

/**
 * Outbound-only zero-width obfuscation.
 *
 * Inserts U+200B / U+200C inside lexicon hits so the wire body no longer carries the literal
 * term, while the visible text and token boundaries stay intact. Inbound SSE is never reversed.
 */
object ZeroWidth {
    const val ZWSP: Char = '\u200B'
    const val ZWNJ: Char = '\u200C'
    const val ZWSP_STR: String = "\u200B"
    const val ZWNJ_STR: String = "\u200C"

    fun isZeroWidth(ch: Char): Boolean = ch == ZWSP || ch == ZWNJ

    /** Lossless inverse used by tests/tooling; never applied on the inbound path. */
    fun strip(text: String): String = buildString(text.length) {
        for (ch in text) if (!isZeroWidth(ch)) append(ch)
    }
}

/** Character trie; longest-match wins so overlapping lexicon terms stay deterministic. */
class ObfuscationTrie(terms: Iterable<String>) {

    private class Node {
        val children = HashMap<Char, Node>()
        var terminal = false
    }

    private val root = Node()

    init {
        terms.forEach(::insert)
    }

    val isEmpty: Boolean get() = root.children.isEmpty()

    fun insert(raw: String) {
        val term = raw.trim()
        if (term.isEmpty() || term.startsWith("#")) return
        var node = root
        for (ch in term) node = node.children.getOrPut(ch) { Node() }
        node.terminal = true
    }

    /** Length of the longest lexicon hit starting at [start]; 0 when nothing matches. */
    fun longestMatchAt(text: String, start: Int): Int {
        var node = root
        var index = start
        var best = 0
        while (index < text.length) {
            node = node.children[text[index]] ?: break
            index++
            if (node.terminal) best = index - start
        }
        return best
    }

    companion object {
        fun fromLexiconText(text: String): ObfuscationTrie =
            ObfuscationTrie(text.lineSequence().map { it.substringBefore('#') }.toList())
    }
}

/** Applies zero-width insertion for lexicon hits only. Non-matching text is returned verbatim. */
class TextObfuscator(
    private val trie: ObfuscationTrie,
    val enabled: Boolean = true,
) {

    fun obfuscate(text: String): String {
        if (!enabled || text.isEmpty() || trie.isEmpty) return text
        val out = StringBuilder(text.length + 16)
        var index = 0
        while (index < text.length) {
            val hit = trie.longestMatchAt(text, index)
            if (hit <= 0) {
                out.append(text[index])
                index++
                continue
            }
            for (offset in 0 until hit) {
                out.append(text[index + offset])
                if (offset != hit - 1) {
                    out.append(if (offset % 2 == 0) ZeroWidth.ZWSP else ZeroWidth.ZWNJ)
                }
            }
            index += hit
        }
        return out.toString()
    }

    companion object {
        /** Asset shipped with the module; a hot-updated copy may override it at runtime. */
        const val ASSET_PATH: String = "obfuscation_lexicon.txt"

        fun fromLexiconText(text: String, enabled: Boolean = true): TextObfuscator =
            TextObfuscator(ObfuscationTrie.fromLexiconText(text), enabled)
    }
}

/**
 * Hot-updatable handle: [hotFile] (updated lexicon) wins over the packaged [assetReader] fallback.
 * Callers only hold the volatile [active] obfuscator, so a refresh never mutates in-flight requests.
 */
class ObfuscationLexiconStore(
    private val assetReader: () -> String,
    private val hotFile: File? = null,
    private val enabled: Boolean = true,
) {

    @Volatile
    private var current: TextObfuscator = load()

    val active: TextObfuscator get() = current

    private fun load(): TextObfuscator {
        val hot = runCatching { hotFile?.takeIf { it.isFile && it.length() > 0 }?.readText() }.getOrNull()
        val text = hot ?: runCatching { assetReader() }.getOrDefault("")
        return TextObfuscator.fromLexiconText(text.orEmpty(), enabled)
    }

    /** Persists and activates a new lexicon; returns the obfuscator now in force. */
    fun update(lexiconText: String): TextObfuscator {
        runCatching {
            hotFile?.parentFile?.mkdirs()
            hotFile?.writeText(lexiconText)
        }
        return TextObfuscator.fromLexiconText(lexiconText, enabled).also { current = it }
    }

    fun refresh(): TextObfuscator = load().also { current = it }
}
