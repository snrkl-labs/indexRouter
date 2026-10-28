package org.snrkl.indexrouter.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskLineFormatterTest {

    private val date = LocalDate.of(2026, 7, 28)

    @Test
    fun `emits checkbox, tags and created-date signifier in vault order`() {
        assertEquals(
            "- [ ] Call the vet #task #index ➕ 2026-07-28",
            TaskLineFormatter.format("Call the vet", date),
        )
    }

    @Test
    fun `strips the trailing full stop the transcriber adds`() {
        assertTrue(TaskLineFormatter.format("Buy milk.", date).startsWith("- [ ] Buy milk #task"))
    }

    @Test
    fun `tags are configurable and gain a leading hash if omitted`() {
        assertEquals(
            "- [ ] Ping Sam #task #ring ➕ 2026-07-28",
            TaskLineFormatter.format("Ping Sam", date, tags = listOf("task", "#ring")),
        )
    }

    @Test
    fun `created date can be suppressed`() {
        assertEquals(
            "- [ ] Water the plants #task #index",
            TaskLineFormatter.format("Water the plants", date, includeCreatedDate = false),
        )
    }
}
