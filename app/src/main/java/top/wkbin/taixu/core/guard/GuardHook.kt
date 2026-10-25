package top.wkbin.taixu.core.guard

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 模块 3 的 JNI 入口：libguard_hook.so（ByteHook，PLT/GOT hook）。
 *
 * 设计约束：
 * 1. 默认关闭——只有设置页开关为 ON 且 so 真的随包安装时才加载并安装 hook；
 * 2. hook 只作用于本进程，`bytehook_hook_all` 不向其他应用注入任何东西；
 * 3. 命中的路径必须在私有目录里有安全副本，否则原样放行（不改行为）；
 * 4. hook 一旦装上是进程级的，关掉开关不会卸载（`sync(false)` 直接返回），
 *    语义上等价于「本次进程内仍然生效」，设置页会提示重启后彻底失效。
 */
object GuardHook {

    private const val TAG = "TaiXuGuard"
    private const val LIB_NAME = "guard_hook"
    private const val ASSET_MANIFEST = "guard/guard_manifest.txt"
    private const val SAFE_DIR = "guard_safe"

    @Volatile
    private var initialized = false

    /** so 是否随包安装（决定设置页那一栏能不能开）。 */
    fun isPackaged(context: Context): Boolean = runCatching {
        val dir = context.applicationInfo?.nativeLibraryDir ?: return@runCatching false
        File(dir, "lib$LIB_NAME.so").isFile
    }.getOrDefault(false)

    /**
     * 与设置页开关同步：ON 时加载 so、把 assets 安全副本种到私有目录、装 hook；
     * OFF 或已初始化时什么都不做。
     *
     * @return 安装的规则条数；未安装返回 0。
     */
    fun sync(context: Context, enabled: Boolean): Result<Int> {
        if (!enabled) return Result.success(0)
        if (initialized) return Result.success(0)
        return runCatching {
            if (!isPackaged(context)) {
                Log.i(TAG, "lib$LIB_NAME.so not packaged; guard stays inactive")
                return@runCatching 0
            }
            System.loadLibrary(LIB_NAME)
            val manifest = readAsset(context, ASSET_MANIFEST)
            val safeRoot = seedSafeCopies(context, manifest)
            val rules = nativeInit(manifest, safeRoot.absolutePath)
            initialized = rules >= 0
            Log.i(TAG, "initialized rules=$rules safeRoot=${safeRoot.absolutePath}")
            rules
        }.onFailure { Log.w(TAG, "guard init failed: ${it.message}", it) }
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    /** 把 manifest 里引用的安全副本从 assets 复制到私有目录，返回副本根目录。 */
    private fun seedSafeCopies(context: Context, manifest: String): File {
        val dir = File(context.filesDir, SAFE_DIR).apply { mkdirs() }
        manifest.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val name = trimmed.substringAfter('\t', "").trim()
            if (name.isEmpty()) return@forEach
            runCatching {
                context.assets.open("guard/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            }.onFailure { Log.w(TAG, "seed $name failed: ${it.message}") }
        }
        return dir
    }

    /** 对应 cpp 的 `Java_top_wkbin_taixu_core_guard_GuardHook_nativeInit`。 */
    private external fun nativeInit(manifest: String, safeRoot: String): Int
}
