package top.wkbin.taixu.harness.routing

/**
 * 路由可调参数。
 *
 * 这些是**工程起点**，不是普适最优：Top-K 只影响候选暴露范围，
 * 不允许被用来隐性偏好"已连接/已缓存"的工具。
 */
internal data class RoutingConfig(
    /** 默认暴露的候选数量。 */
    val topK: Int = 4,
    /** 首选明显领先时收缩到的最小候选数。 */
    val minTopK: Int = 3,
    /** 前列分数胶着时放宽到的最大候选数。 */
    val maxTopK: Int = 5,
    /** 低于该分差视为"胶着"，需要更多候选让模型自己判断。 */
    val closeScoreGap: Double = 0.03,
    /** 高于该分差视为"首选明显占优"，可以只给少量替代项。 */
    val clearLeaderGap: Double = 0.12,
    /** BM25 召回上限。 */
    val bm25CandidateLimit: Int = 20,
)
