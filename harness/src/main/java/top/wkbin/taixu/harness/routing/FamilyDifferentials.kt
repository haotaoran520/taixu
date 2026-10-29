package top.wkbin.taixu.harness.routing

/** 一条同族横向差异：维度、本工具取值、同族对照项取值。 */
internal data class FamilyDelta(
    val dimension: String,
    val ownValue: String,
    val peerToolId: String,
    val peerValue: String,
) {
    fun render(): String = "$dimension=$ownValue（对照 $peerToolId 为 $peerValue）"
}

/**
 * 同族横向差异计算。
 *
 * 只比较 [ToolDescriptor.familyDifferentiators] 里**已声明**的字段，取值原样引用，
 * 因此每条差异都可回溯到注册表事实。没有差异字段时返回空，绝不推测"更快/更稳"。
 */
internal object FamilyDifferentials {

    /** 找出与 [tool] 同族且在该维度取值不同的候选，返回可直接写进理由的差异列表。 */
    fun deltas(
        tool: ToolDescriptor,
        peers: List<ToolDescriptor>,
        limit: Int = 2,
    ): List<FamilyDelta> {
        if (tool.familyDifferentiators.isEmpty() || tool.familyId.isBlank()) return emptyList()
        val sameFamily = peers.filter { it.toolId != tool.toolId && it.familyId == tool.familyId }
        if (sameFamily.isEmpty()) return emptyList()

        val result = ArrayList<FamilyDelta>()
        tool.familyDifferentiators.forEach { (dimension, ownValue) ->
            val differing = sameFamily.firstOrNull { peer ->
                val peerValue = peer.familyDifferentiators[dimension]
                peerValue != null && peerValue != ownValue
            } ?: return@forEach
            result += FamilyDelta(
                dimension = dimension,
                ownValue = ownValue,
                peerToolId = differing.toolId,
                peerValue = differing.familyDifferentiators.getValue(dimension),
            )
        }
        return result.take(limit)
    }

    /**
     * 覆盖关系：本工具支持、同族对照不支持的维度（用于回答"它比替代项强在哪"）。
     * 只在对方**显式声明**了该维度且取值不同时才成立。
     */
    fun peerGaps(tool: ToolDescriptor, peers: List<ToolDescriptor>): List<String> {
        if (tool.familyId.isBlank()) return emptyList()
        return peers.filter { it.toolId != tool.toolId && it.familyId == tool.familyId }
            .flatMap { peer ->
                val missing = tool.capabilities.operations - peer.capabilities.operations
                missing.map { "同族 ${peer.toolId} 不具备操作 $it" }
            }
            .distinct()
            .take(2)
    }
}
