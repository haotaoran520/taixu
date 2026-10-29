package top.wkbin.taixu.harness.directory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * `use_capability` 工具的参数 schema。
 *
 * 独立成对象的原因有二：
 * 1. 参数定义是**模型可见的契约**，必须与实现里真正读取的参数一一对应——
 *    曾经出现过实现读 `auto_route`、schema 却没声明，导致调用被严格校验直接拒绝；
 * 2. ProviderClient 已经很大，抽出后便于单测守护（见 CapabilityToolSchemaTest）。
 */
internal object CapabilityToolSchema {

    val parameters: JsonObject = Json.parseToJsonElement(
        """
        {"type":"object","properties":{
          "action":{"type":"string","enum":["list","inspect","call","decline","script","route"],"description":"list=列出能力域；inspect=查看某能力域的工具清单；call=调用工具；decline=放弃某能力；script=执行 JS 脚本批量/循环调用能力；route=只读路由，用 goal 在已缓存工具中收敛 Top-K 候选并给出分数拆解与证据化理由（不启动进程）"},
          "server":{"type":"string","description":"能力域 id（list 时省略；inspect/call/decline 必填）"},
          "tool":{"type":"string","description":"目标工具名（call/decline 必填；inspect 省略则列出该能力域全部工具）"},
          "arguments":{"type":"object","description":"call 时传给目标工具的参数对象，结构与 inspect 输出的参数 schema 一致"},
          "code":{"type":"string","description":"script 时必填的 JS 脚本；全局对象 capability.call(server, tool, args) 返回 {ok, output}；注意不支持顶层 return（程序模式下是语法错误），最后一条表达式的完成值即脚本返回值（字符串或对象）"},
          "timeout_seconds":{"type":"integer","minimum":1,"maximum":300,"description":"script 的整体超时，默认 60 秒"},
          "auto_route":{"type":"boolean","description":"call 专用：true 时启用「执行→验收→失败分类→同服务内重路由」闭环；默认 false，行为与普通 call 完全一致"},
          "goal":{"type":"string","description":"route/call(auto_route) 的子任务描述，用于召回与打分"},
          "subtask_id":{"type":"string","description":"可选的子任务标识，写入路由与决策日志便于关联"},
          "task_class":{"type":"string","description":"任务类别，用于取同类实测统计（无该类别数据时显式标未知）"},
          "operations":{"type":"string","description":"必需操作，逗号分隔，如 read,search"},
          "domains":{"type":"string","description":"必需领域，逗号分隔，如 web,database"},
          "input_types":{"type":"string","description":"必需输入类型，逗号分隔"},
          "output_types":{"type":"string","description":"期望输出类型，逗号分隔"},
          "read_only":{"type":"boolean","description":"true 时排除会改变状态或需要确认的工具"},
          "fresh":{"type":"boolean","description":"true 时要求实时数据能力（freshness=live_fetch）"},
          "expect_contains":{"type":"string","description":"call(auto_route) 的验收内容，逗号分隔；命中全部关键字才算通过"},
          "format":{"type":"string","enum":["routing","injection","json"],"description":"route 输出格式：routing=排名摘要（默认）；injection=6.4 规范候选注入块（含 Schema 与选择规则）；json=结构化 JSON"},
          "variant":{"type":"string","enum":["A_ALL_TOOLS","B_RETRIEVAL","C_RANKING","D_RANKING_WITH_RATIONALE","E_WITH_VERIFICATION","F_WITH_HISTORY"],"description":"route 的注入变体，用于 A–F 消融实验；默认 D"}
        },"required":["action"]}
        """.trimIndent(),
    ).jsonObject
}
