package top.wkbin.taixu.core.network

import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 出站零宽混淆的运行时句柄（模块 1）。
 *
 * 设置页写开关后立刻对下一次请求生效：不需要重启进程，也不改变 [TextObfuscator] 的不可变语义
 * ——所有调用方只读取 [current] 返回的快照，因而热更新词库不会影响在途请求。
 *
 * 序言约定：只在出站方向做变换。SSE 入站文本一律原样保留（零宽字符只是渲染不可见，
 * 提前 strip 会破坏模型输出的正常排版与代码块缩进）。
 */
object ObfuscationRuntime {

    private val handle = AtomicReference<ObfuscationLexiconStore?>(null)

    @Volatile
    private var enabled: Boolean = false

    /** Binds the packaged lexicon (assets) plus the optional hot-update file. Idempotent. */
    fun attach(assetReader: () -> String, hotFile: File? = null) {
        if (handle.get() == null) {
            handle.set(ObfuscationLexiconStore(assetReader = assetReader, hotFile = hotFile))
        }
    }

    fun isReady(): Boolean = handle.get() != null

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    /** Active obfuscator, or null while the feature is off (callers then send the text verbatim). */
    fun current(): TextObfuscator? = if (enabled) handle.get()?.active else null

    /** Outbound-only transform: returns the input unchanged when the feature is off. */
    fun apply(text: String): String {
        val obfuscator = current() ?: return text
        if (text.isEmpty()) return text
        return obfuscator.obfuscate(text)
    }

    /** Settings preview: always applies, independent of the on/off switch. */
    fun preview(text: String): String {
        val obfuscator = handle.get()?.active ?: return text
        return obfuscator.obfuscate(text)
    }

    /** Activates a user supplied lexicon (hot update) and persists it to the hot file. */
    fun updateLexicon(lexiconText: String): Boolean {
        val store = handle.get() ?: return false
        return runCatching { store.update(lexiconText) }.isSuccess
    }

    /** Re-reads the hot file (falls back to the packaged asset when absent). */
    fun refresh(): Boolean {
        val store = handle.get() ?: return false
        return runCatching { store.refresh() }.isSuccess
    }
}
