package top.wkbin.taixu.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 模块 1 运行时开关：关闭时不改写、打开时只在命中处插入零宽字符，
 * 且 strip 后必须与原文逐字一致（入站/展示都不受影响）。
 */
class ObfuscationRuntimeTest {

    @Before
    fun setUp() {
        ObfuscationRuntime.attach(assetReader = { "敏感词\n测试项" })
        ObfuscationRuntime.setEnabled(false)
    }

    @Test
    fun disabledKeepsTextVerbatim() {
        assertFalse(ObfuscationRuntime.isEnabled())
        val text = "这里有一个敏感词需要处理"
        assertEquals(text, ObfuscationRuntime.apply(text))
    }

    @Test
    fun enabledInsertsZeroWidthOnlyOnHits() {
        ObfuscationRuntime.setEnabled(true)
        val obfuscated = ObfuscationRuntime.apply("这里有一个敏感词需要处理")
        assertTrue(obfuscated.contains(ZeroWidth.ZWSP))
        assertEquals("这里有一个敏感词需要处理", ZeroWidth.strip(obfuscated))
        // 未命中的纯文本原样返回
        assertEquals("普通文本", ObfuscationRuntime.apply("普通文本"))
    }

    @Test
    fun previewIgnoresSwitch() {
        ObfuscationRuntime.setEnabled(false)
        assertTrue(ObfuscationRuntime.preview("敏感词").contains(ZeroWidth.ZWSP))
    }

    @Test
    fun hotUpdateReplacesLexicon() {
        ObfuscationRuntime.setEnabled(true)
        assertTrue(ObfuscationRuntime.updateLexicon("新词条"))
        val obfuscated = ObfuscationRuntime.apply("出现新词条的句子")
        assertTrue(obfuscated.contains(ZeroWidth.ZWSP))
        assertEquals("出现新词条的句子", ZeroWidth.strip(obfuscated))
        // 旧词表已被替换
        assertEquals("敏感词", ObfuscationRuntime.apply("敏感词"))
        ObfuscationRuntime.updateLexicon("敏感词\n测试项")
    }
}
