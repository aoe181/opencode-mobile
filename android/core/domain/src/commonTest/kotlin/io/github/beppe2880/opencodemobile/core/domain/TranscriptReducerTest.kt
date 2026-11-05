package io.github.beppe2880.opencodemobile.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TranscriptReducerTest {

    private val session = "ses_1"
    private val message = "msg_a"
    private val model = ModelRef("haiku", "anthropic")

    private fun reduce(vararg events: ServerEvent.SessionScoped, from: List<Message> = emptyList()) =
        events.fold(from, TranscriptReducer::reduce)

    private fun started() = ServerEvent.StepStarted(session, message, "build", model, startedAt = 10)

    private fun List<Message>.assistant() = single { it.id == message } as Message.Assistant

    @Test
    fun `streams text deltas and settles them when the step ends`() {
        val streaming = reduce(
            started(),
            ServerEvent.TextStarted(session, message, 0),
            ServerEvent.TextDelta(session, message, 0, "Hel"),
            ServerEvent.TextDelta(session, message, 0, "lo"),
        )
        assertEquals(Part.Text(0, "Hello", streaming = true), streaming.assistant().parts.single())
        assertTrue(streaming.assistant().isStreaming)

        val ended = reduce(
            ServerEvent.StepStreamed(session, message, at = 15),
            ServerEvent.StepEnded(session, message, "stop", 0.5, TokenUsage(output = 3), at = 20),
            from = streaming,
        ).assistant()
        assertEquals(Part.Text(0, "Hello", streaming = false), ended.parts.single())
        assertEquals(15L, ended.streamedAt)
        assertEquals(20L, ended.completedAt)
        assertEquals("stop", ended.finish)
        assertFalse(ended.isStreaming)
    }

    @Test
    fun `text ended replaces streamed text with the final value`() {
        val parts = reduce(
            started(),
            ServerEvent.TextDelta(session, message, 0, "draft"),
            ServerEvent.TextEnded(session, message, 0, "final"),
        ).assistant().parts
        assertEquals(listOf(Part.Text(0, "final")), parts)
    }

    @Test
    fun `reasoning keeps timing and falls back to streamed text`() {
        val reasoning = reduce(
            started(),
            ServerEvent.ReasoningStarted(session, message, 0, at = 11),
            ServerEvent.ReasoningDelta(session, message, 0, "thinking"),
            ServerEvent.ReasoningEnded(session, message, 0, text = "", at = 15),
        ).assistant().parts.single()
        assertEquals(Part.Reasoning(0, "thinking", streaming = false, startedAt = 11, completedAt = 15), reasoning)
    }

    @Test
    fun `tool call lifecycle`() {
        val call = "call_1"
        val streamingInput = reduce(
            started(),
            ServerEvent.ToolInputStarted(session, message, call, "read", at = 12),
            ServerEvent.ToolInputDelta(session, message, call, "{\"pa"),
        )
        val streamingTool = streamingInput.assistant().parts.single() as Part.Tool
        assertEquals(ToolStatus.Streaming, streamingTool.status)
        assertEquals("{\"pa", streamingTool.input)

        val done = reduce(
            ServerEvent.ToolInputEnded(session, message, call, "{\"path\": \"a\"}"),
            ServerEvent.ToolCalled(session, message, call, "{\"path\":\"a\"}", at = 13),
            ServerEvent.ToolProgress(session, message, call, mapOf("lines" to "1")),
            ServerEvent.ToolSucceeded(session, message, call, "hello", mapOf("truncated" to "false"), at = 14),
            from = streamingInput,
        ).assistant().parts.single() as Part.Tool
        assertEquals(
            Part.Tool(
                callId = call,
                name = "read",
                status = ToolStatus.Completed,
                input = "{\"path\":\"a\"}",
                output = "hello",
                metadata = mapOf("lines" to "1", "truncated" to "false"),
                startedAt = 12,
                ranAt = 13,
                completedAt = 14,
            ),
            done,
        )
        // The duration counts from when the server ran the call, not from its streaming input.
        assertEquals(1L, done.durationMs)
    }

    @Test
    fun `failed tool and failed step`() {
        val messages = reduce(
            started(),
            ServerEvent.ToolInputStarted(session, message, "c", "edit", at = 12),
            ServerEvent.ToolFailed(session, message, "c", "denied", at = 13),
            ServerEvent.StepFailed(session, message, "provider down"),
        )
        val assistant = messages.assistant()
        assertEquals(ToolStatus.Error, (assistant.parts.single() as Part.Tool).status)
        assertEquals("provider down", assistant.error)
        assertFalse(assistant.isStreaming)
    }

    @Test
    fun `events for unknown messages leave the transcript untouched`() {
        val transcript = listOf<Message>(Message.User("msg_u", 1, "hi"))
        assertSame(transcript, TranscriptReducer.reduce(transcript, ServerEvent.TextDelta(session, "msg_x", 0, "x")))
    }

    @Test
    fun `queued prompts are pending until delivered and removed when cancelled`() {
        val queued = reduce(
            ServerEvent.InboxEnqueued(session, "msg_1", "first", at = 1),
            ServerEvent.InboxEnqueued(session, "msg_2", "second", at = 2),
            ServerEvent.InboxEnqueued(session, "msg_1", "first", at = 1),
        )
        assertEquals(listOf("msg_1", "msg_2"), queued.map { it.id })
        assertTrue(queued.all { (it as Message.User).pending })

        val after = reduce(
            ServerEvent.InboxDelivered(session, "msg_1"),
            ServerEvent.InboxCancelled(session, "msg_2"),
            from = queued,
        )
        assertEquals(listOf(Message.User("msg_1", 1, "first", pending = false)), after)
    }

    @Test
    fun `merge keeps local pending prompts and live steps missing on the server`() {
        val stored = Message.User("msg_u", 1, "hi")
        val live = reduce(started(), ServerEvent.InboxEnqueued(session, "msg_q", "next", at = 30))
        val completedLocal = Message.Assistant("msg_old", 0, "build", model, emptyList(), completedAt = 5)

        val merged = TranscriptReducer.merge(listOf(stored), live + completedLocal)

        assertEquals(listOf("msg_u", message, "msg_q"), merged.map { it.id })
    }
}
