package top.wkbin.taixu.harness

/**
 * 设置页可调开关的进程内镜像。
 *
 * 设置页把开关写进独立 DataStore（`IterationTuningStore`），app 层把 Flow 同步到这里，
 * harness 在热路径上只读这个 volatile 值——既不引入协程开销，也不让 harness 依赖 DataStore。
 */
object IterationTuningFlags {

    /** 模块 4：关闭后不再写入成功策略，也不再往 system prompt 尾部拼 few-shot。 */
    @Volatile
    var strategyReplayEnabled: Boolean = true
}
