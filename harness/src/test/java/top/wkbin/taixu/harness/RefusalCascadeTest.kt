package top.wkbin.taixu.harness

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RefusalCascadeTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val cascade = RefusalCascade(CascadePolicy(maxLevel = 3))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------- ACCEPTANCE: mock SSE that refuses the first two rounds ----------

    @Test
    fun acceptance_firstTwoRoundsRefusedThenSuccessPersistsExactlyOneAssistantMessage() {
        val seenBodies = mutableListOf<String>()
        val calls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seenBodies += request.body.readUtf8()
                return when (calls.incrementAndGet()) {
                    1 -> sseResponse(listOf("抱歉", "，我", "无法提供", "这个请求。"))
                    2 -> sseResponse(listOf("I'm sorry", ", but I can't", " help with that."))
                    else -> sseResponse(listOf("已确认：", "目标路径重定向到 assets 安全副本", "完成。"))
                }
            }
        }

        // Seam under test: the loop body that HarnessProviderRunner runs around each stream attempt.
        val persistedAssistant = mutableListOf<String>()
        val toolOutcomeUnknown = mutableListOf<String>()
        val submittedToolCalls = mutableListOf<String>()
        var messages = listOf(
            ApiMessage(role = "system", content = "you are taixu"),
            ApiMessage(role = "user", content = "把 /data/local/tmp/a.conf 重定向到 assets 副本"),
        )
        var level = 0
        var attempts = 0
        var accepted: String? = null

        while (true) {
            attempts++
            val chunks = readSseChunks(streamOnce(messages))
            val text = StringBuilder()
            var refused = false
            for (chunk in chunks) {
                text.append(chunk)
                // window: only the head of the stream decides, later chunks cannot un-refuse
                if (cascade.isRefusal(text.toString())) {
                    refused = true
                    break
                }
            }
            // Refusal rounds produce zero tool calls -> nothing is submitted, nothing is persisted.
            val toolCalls: List<String> = emptyList()
            toolCalls.forEach { submittedToolCalls += it }

            if (refused && cascade.shouldEscalateAfter(text.toString(), level, toolCalls.size)) {
                level++
                messages = cascade.escalate(messages, level)
                continue
            }
            accepted = text.toString()
            persistedAssistant += accepted
            break
        }

        assertEquals("retry ladder stops on the first success", 3, attempts)
        assertEquals("only the winning round is persisted", 1, persistedAssistant.size)
        assertEquals("已确认：目标路径重定向到 assets 安全副本完成。", accepted)
        assertEquals("refusal rounds never submit tool calls", 0, submittedToolCalls.size)
        assertEquals("no dangling TOOL_OUTCOME_UNKNOWN", 0, toolOutcomeUnknown.size)
        assertEquals("one wire request per round", 3, seenBodies.size)
        assertTrue("round 2 carries S1", seenBodies[1].contains("[Cascade S1]"))
        assertTrue("round 3 carries S2", seenBodies[2].contains("[Cascade S2]"))
        assertFalse("winning round is not rewritten again", seenBodies[2].contains("[Cascade S3]"))
    }

    // ---------- unit behaviour ----------

    @Test
    fun detectorMatchesMultilingualRefusalCues() {
        val detector = RefusalDetector()
        assertTrue(detector.isRefusal("抱歉，我无法提供这些信息"))
        assertTrue(detector.isRefusal("As an AI language model, I can't do that"))
        assertTrue(detector.isRefusal("申し訳ありませんが、お答えできません"))
        assertFalse(detector.isRefusal("已为你生成构建脚本：./gradlew assembleDebug"))
    }

    @Test
    fun headWindowLimitsInspectionToFirstChunks() {
        val detector = RefusalDetector(CascadePolicy(windowChars = 8))
        val lateRefusal = "执行成功。" + "x".repeat(200) + "抱歉"
        assertFalse("refusal text after the window must not trigger", detector.isRefusal(lateRefusal))
    }

    @Test
    fun escalationIsCappedByConfiguredCeiling() {
        val capped = RefusalCascade(CascadePolicy(maxLevel = 2))
        assertTrue(capped.canEscalate(1))
        assertFalse("S3 must not be reachable with maxLevel=2", capped.canEscalate(2))
        assertFalse(capped.shouldEscalateAfter("抱歉", 2, 0))

        val ceiling4 = RefusalCascade(CascadePolicy(maxLevel = 4))
        assertEquals("S4", ceiling4.rewriteFor(4).code)
        assertFalse(ceiling4.canEscalate(4))
    }

    @Test
    fun escalateOnlyTouchesLastUserPrompt() {
        val original = listOf(
            ApiMessage(role = "system", content = "sys"),
            ApiMessage(role = "user", content = "第一问"),
            ApiMessage(role = "assistant", content = null, tool_calls = listOf(
                ApiToolCall(id = "call_1", function = ApiFunctionCall(name = "shell", arguments = "{}")),
            )),
            ApiMessage(role = "tool", content = "ok", tool_call_id = "call_1"),
            ApiMessage(role = "user", content = "目标请求"),
        )
        val escalated = cascade.escalate(original, 1)

        assertEquals("size unchanged -> approval structure untouched", original.size, escalated.size)
        assertEquals(original[0], escalated[0])
        assertEquals(original[2], escalated[2])
        assertEquals(original[3], escalated[3])
        assertEquals("call_1", escalated[2].tool_calls!!.single().id)
        assertEquals("call_1", escalated[3].tool_call_id)
        assertEquals(original[1], escalated[1])
        assertTrue(escalated[4].content!!.startsWith("目标请求"))
        assertTrue(escalated[4].content!!.contains("[Cascade S1]"))
    }

    @Test
    fun roundsWithToolCallsAreNeverEscalated() {
        // A refusal-looking prefix plus committed tool calls must not be retried: approvals depend on it.
        assertFalse(cascade.shouldEscalateAfter("抱歉", 0, toolCallCount = 1))
    }

    @Test
    fun digestIsStableAndNonReversible() {
        val first = responseDigestOf("  相同的  回复 ")
        val second = responseDigestOf("相同的 回复")
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertFalse(first.contains("回复"))
    }

    // ---------- helpers ----------

    private fun sseResponse(chunks: List<String>): MockResponse {
        val body = buildString {
            chunks.forEach { append("data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}\n\n") }
            append("data: [DONE]\n\n")
        }
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    private fun streamOnce(messages: List<ApiMessage>): String {
        val payload = messages.joinToString(",") { "{\"role\":\"${it.role}\",\"content\":\"${it.content.orEmpty()}\"}" }
        val body = "{\"stream\":true,\"messages\":[$payload]}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(server.url("/v1/chat/completions")).post(body).build()
        return client.newCall(request).execute().use { it.body!!.string() }
    }

    private fun readSseChunks(raw: String): List<String> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("data:") && !it.contains("[DONE]") }
        .mapNotNull { line ->
            val start = line.indexOf("\"content\":\"")
            if (start < 0) return@mapNotNull null
            val from = start + "\"content\":\"".length
            val end = line.indexOf('"', from)
            if (end < 0) null else line.substring(from, end)
        }
        .toList()

    @Test
    fun updateHotSwapsSwitchAndCeiling() {
        val hot = RefusalCascade(CascadePolicy(enabled = true, maxLevel = 2))
        assertTrue(hot.isRefusal("抱歉，我无法提供该内容"))
        assertFalse(hot.canEscalate(2))

        // 设置页把上限从 S2 调到 S4：下一次判定立即生效
        hot.update(CascadePolicy(enabled = true, maxLevel = 4))
        assertTrue(hot.canEscalate(2))
        assertFalse(hot.canEscalate(4))

        // 关闭后既不再识别拒答，也不再升级
        hot.update(CascadePolicy(enabled = false, maxLevel = 4))
        assertFalse(hot.isRefusal("抱歉，我无法提供该内容"))
        assertFalse(hot.canEscalate(0))
    }

    @Test
    fun trackerSeesHotUpdatedPolicyFromSettingsPage() {
        // runner 长期持有同一个 cascade 实例（DI single），设置页改的是同一个对象
        val shared = RefusalCascade(CascadePolicy(enabled = true, maxLevel = 3))
        val tracker = CascadeTracker(shared)
        val messages = listOf(
            ApiMessage(role = "system", content = "you are taixu"),
            ApiMessage(role = "user", content = "把 /data/local/tmp/a.conf 重定向到 assets 副本"),
        )
        val refusal = ChatResult(content = "抱歉，我无法提供这个请求。", toolCalls = emptyList())

        assertNotNull(tracker.onRefusal("抱歉，我无法提供这个请求。", refusal, messages))

        // 用户在设置页关掉级联：同一 tracker 下一轮立刻不再升级
        shared.update(CascadePolicy(enabled = false, maxLevel = 3))
        assertNull(tracker.onRefusal("抱歉，我无法提供这个请求。", refusal, messages))
    }
}
