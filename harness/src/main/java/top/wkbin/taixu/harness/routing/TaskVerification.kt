package top.wkbin.taixu.harness.routing

import kotlinx.serialization.json.Json

/** 验收检查类型：规则可执行、可解释，不依赖模型自评（方案 7.1）。 */
internal enum class VerificationKind {
    NON_EMPTY,
    MIN_LENGTH,
    CONTAINS_ALL,
    JSON_OBJECT,
    HAS_SOURCE,
    REGEX,
    SIDE_EFFECT_CONFIRMED,
}

/** 失败层面：内容不合格（输出质量）与覆盖不足（任务未完成）需要分开归因。 */
internal enum class VerificationAspect {
    CONTENT,
    COVERAGE,
}

internal data class VerificationCheck(
    val kind: VerificationKind,
    val params: List<String> = emptyList(),
    val description: String = "",
    val aspect: VerificationAspect = VerificationAspect.CONTENT,
)

/** 子任务验收条件集合。空集合表示"未声明"，此时只做通用健全性检查。 */
internal data class SubtaskAcceptance(val checks: List<VerificationCheck> = emptyList())

/** 工具产出：只放可从执行结果直接观察到的事实。 */
internal data class ToolOutput(
    val text: String = "",
    val fields: Map<String, String> = emptyMap(),
    val truncated: Boolean = false,
    val sourceUrl: String? = null,
    /** 写操作专用：副作用是否已确认发生（由状态查询或回执确认）。 */
    val sideEffectConfirmed: Boolean? = null,
)

internal data class VerificationOutcome(
    val passed: Boolean,
    val failures: List<String>,
    val observations: List<String>,
    /** 失败所属层面：供失败分类器区分"输出质量"与"任务未完成"。 */
    val failedAspects: Set<VerificationAspect> = emptySet(),
) {
    companion object {
        fun pass(note: String = "验收条件均满足") = VerificationOutcome(true, emptyList(), listOf(note))
        fun fail(vararg reasons: String) = VerificationOutcome(false, reasons.toList(), emptyList())
    }
}

/**
 * 结果验收器（方案 7.1）：**调用成功 ≠ 任务完成**。
 *
 * 这里只做可判定的客观校验：结构、空值、截断、来源、正则、副作用确认。
 * 语义级判断（"是否真的回答了用户"）留给模型评估器，但必须叠加在规则校验之上。
 */
internal object ResultVerifier {

    fun verify(output: ToolOutput, acceptance: SubtaskAcceptance): VerificationOutcome {
        val failures = ArrayList<String>()
        val observations = ArrayList<String>()
        val aspects = LinkedHashSet<VerificationAspect>()

        // 通用健全性：空返回与截断一律不算完成。
        val hasContent = output.text.isNotBlank() || output.fields.isNotEmpty()
        if (!hasContent) {
            failures += "返回内容为空"
            aspects += VerificationAspect.CONTENT
        }
        if (output.truncated) {
            failures += "返回被截断，不满足完整读取要求"
            aspects += VerificationAspect.CONTENT
        }

        acceptance.checks.forEach { check ->
            when (check.kind) {
                VerificationKind.NON_EMPTY -> if (!hasContent) { failures += label(check, "返回非空"); aspects += check.aspect }
                VerificationKind.MIN_LENGTH -> {
                    val min = check.params.firstOrNull()?.toIntOrNull() ?: 0
                    if (output.text.length < min) { failures += label(check, "长度需 ≥ $min，实际 ${output.text.length}"); aspects += check.aspect }
                }
                VerificationKind.CONTAINS_ALL -> {
                    val missing = check.params.filterNot { output.text.contains(it) }
                    if (missing.isNotEmpty()) { failures += label(check, "缺少必需内容：${missing.joinToString("/")}"); aspects += check.aspect }
                }
                VerificationKind.JSON_OBJECT -> {
                    val parsed = runCatching { Json.parseToJsonElement(output.text.trim()) }.isSuccess
                    if (!parsed) { failures += label(check, "返回不是合法 JSON"); aspects += check.aspect }
                }
                VerificationKind.HAS_SOURCE -> {
                    val url = output.sourceUrl.orEmpty()
                    if (!url.startsWith("http")) {
                        failures += label(check, "缺少可核验来源")
                        aspects += check.aspect
                    } else {
                        observations += "来源：$url"
                    }
                }
                VerificationKind.REGEX -> {
                    val pattern = check.params.firstOrNull().orEmpty()
                    val matched = pattern.isNotBlank() && runCatching {
                        Regex(pattern).containsMatchIn(output.text)
                    }.getOrDefault(false)
                    if (!matched) { failures += label(check, "未匹配模式 $pattern"); aspects += check.aspect }
                }
                VerificationKind.SIDE_EFFECT_CONFIRMED -> {
                    if (output.sideEffectConfirmed != true) {
                        failures += label(check, "写入副作用未确认")
                        aspects += check.aspect
                    } else {
                        observations += "副作用已确认"
                    }
                }
            }
        }

        return if (failures.isEmpty()) {
            VerificationOutcome(true, emptyList(), observations.ifEmpty { listOf("验收条件均满足") })
        } else {
            VerificationOutcome(false, failures, observations, aspects.toSet())
        }
    }

    private fun label(check: VerificationCheck, detail: String): String =
        if (check.description.isBlank()) detail else "${check.description}：$detail"
}
