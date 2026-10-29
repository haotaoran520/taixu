package top.wkbin.taixu.harness.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.McpToolAnnotations
import top.wkbin.taixu.core.model.McpToolInfo

class ToolDescriptorFactoryTest {

    private fun info(
        name: String,
        description: String,
        annotations: McpToolAnnotations? = null,
    ) = McpToolInfo(
        serverId = "mcp_sqlite",
        serverName = "SQLite",
        name = name,
        description = description,
        annotations = annotations,
    )

    @Test
    fun `读操作按名称推断为只读并归入能力标签`() {
        val descriptor = ToolDescriptorFactory
            .fromMcpTools("mcp_sqlite", "SQLite", listOf(info("query", "read rows from database table")))
            .single()

        assertEquals("mcp_sqlite.query", descriptor.toolId)
        assertTrue(descriptor.capabilities.readOnly)
        assertTrue(descriptor.capabilities.domains.contains("database"))
        assertTrue(descriptor.capabilities.operations.contains("read"))
        assertEquals(EvidenceState.INFERRED, descriptor.capabilityEvidence)
        assertEquals(SideEffectLevel.NONE, descriptor.policy.sideEffectLevel)
    }

    @Test
    fun `服务端注解优先于名称推断`() {
        val descriptor = ToolDescriptorFactory
            .fromMcpTools(
                "mcp_sqlite",
                "SQLite",
                listOf(
                    info(
                        "execute_sql",
                        "execute arbitrary sql",
                        McpToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false),
                    ),
                ),
            )
            .single()

        assertFalse(descriptor.capabilities.readOnly)
        assertEquals(EvidenceState.DECLARED, descriptor.capabilityEvidence)
        assertEquals(SideEffectLevel.IRREVERSIBLE, descriptor.policy.sideEffectLevel)
        assertTrue(descriptor.policy.requiresConfirmation)
        assertTrue(descriptor.constraints.any { it.contains("破坏性") })
    }

    @Test
    fun `被禁用的工具标记为不可用而不是降分`() {
        val descriptor = ToolDescriptorFactory
            .fromMcpTools(
                "mcp_sqlite",
                "SQLite",
                listOf(info("list_tables", "list tables from database")),
                enabledToolNames = emptySet(),
            )
            .single()

        assertEquals(AvailabilityState.DISABLED, descriptor.availability)
        assertFalse(descriptor.isEligible)
    }

    @Test
    fun `同族判定把同类工具归入同一族`() {
        val descriptors = ToolDescriptorFactory.fromMcpTools(
            "mcp_sqlite",
            "SQLite",
            listOf(
                info("query_a", "read data from database"),
                info("query_b", "read rows in database"),
            ),
        )

        assertEquals(descriptors[0].familyId, descriptors[1].familyId)
    }
}
