package top.wkbin.taixu.core.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextObfuscatorTest {

    private val lexicon = """
        # comment line is ignored
        敏感词
        敏感词测试项
        违禁词测试项
    """.trimIndent()

    private fun obfuscator() = TextObfuscator.fromLexiconText(lexicon)

    /** ACCEPTANCE: repr contains U+200B and strip() restores the exact original. */
    @Test
    fun acceptance_obfuscateContainsZeroWidthAndStripRestoresOriginal() {
        val original = "敏感词"
        val output = obfuscator().obfuscate(original)

        assertTrue("expected U+200B inside ${output.repr()}", output.contains(ZeroWidth.ZWSP))
        assertEquals(original, ZeroWidth.strip(output))
        assertEquals("visible text must be unchanged", original, output.filterNot(ZeroWidth::isZeroWidth))
    }

    @Test
    fun nonLexiconTextIsReturnedVerbatim() {
        val text = "今天天气不错，没有命中任何词条。"
        assertEquals(text, obfuscator().obfuscate(text))
    }

    @Test
    fun surroundingTextAndTokenBoundariesArePreserved() {
        val text = "前缀敏感词后缀"
        val output = obfuscator().obfuscate(text)

        assertTrue(output.startsWith("前缀"))
        assertTrue(output.endsWith("后缀"))
        assertEquals(text, ZeroWidth.strip(output))
        assertEquals('敏', output[2])
        assertEquals(ZeroWidth.ZWSP, output[3])
        assertEquals('感', output[4])
    }

    @Test
    fun longestMatchWinsForOverlappingTerms() {
        val output = obfuscator().obfuscate("敏感词测试项")
        // 5 separators for the 6-char longest term, not 2 for the 3-char prefix "敏感词".
        assertEquals(5, output.count(ZeroWidth::isZeroWidth))
        assertEquals("敏感词测试项", ZeroWidth.strip(output))
    }

    @Test
    fun disabledObfuscatorNeverRewrites() {
        val inactive = TextObfuscator.fromLexiconText(lexicon, enabled = false)
        assertEquals("敏感词", inactive.obfuscate("敏感词"))
    }

    @Test
    fun hotUpdateReplacesLexiconAndPersists() {
        val hot = File.createTempFile("lexicon", ".txt").also { it.deleteOnExit() }
        val store = ObfuscationLexiconStore(assetReader = { lexicon }, hotFile = hot)

        assertTrue(store.active.obfuscate("敏感词").contains(ZeroWidth.ZWSP))
        assertFalse(store.active.obfuscate("新词").contains(ZeroWidth.ZWSP))

        store.update("新词\n")
        assertTrue(hot.readText().contains("新词"))
        assertTrue(store.active.obfuscate("新词").contains(ZeroWidth.ZWSP))
        assertEquals("敏感词", store.active.obfuscate("敏感词"))
    }

    private fun String.repr(): String = buildString {
        append('"')
        for (ch in this@repr) {
            when (ch) {
                ZeroWidth.ZWSP -> append("\\u200B")
                ZeroWidth.ZWNJ -> append("\\u200C")
                else -> append(ch)
            }
        }
        append('"')
    }
}
