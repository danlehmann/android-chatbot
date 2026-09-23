package net.daniellehmann.localchat

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.daniellehmann.localchat.api.ApiException
import net.daniellehmann.localchat.api.ChatMessage
import net.daniellehmann.localchat.api.OpenAiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiClientTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun base() = server.url("/v1").toString()

    @Test
    fun listsModelsSortedWithAuth() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"zeta"},{"id":"alpha"}]}"""))
        server.enqueue(MockResponse().setResponseCode(404)) // /props probe
        val models = OpenAiClient(base(), "secret").listModels()
        assertEquals(listOf("alpha", "zeta"), models.map { it.id })
        assertEquals(listOf(null, null), models.map { it.contextLength })
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer secret", req.getHeader("Authorization"))
    }

    @Test
    fun readsContextLengthFromModelsOrProps() = runBlocking {
        // vLLM reports max_model_len directly.
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"q","max_model_len":131072}]}"""))
        assertEquals(131072, OpenAiClient(base(), "").listModels().single().contextLength)
        server.takeRequest()
        // llama.cpp reports the configured window on /props.
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"l"}]}"""))
        server.enqueue(MockResponse().setBody("""{"default_generation_settings":{"n_ctx":32768}}"""))
        assertEquals(32768, OpenAiClient(base(), "").listModels().single().contextLength)
        server.takeRequest()
        assertEquals("/props", server.takeRequest().path)
    }

    @Test
    fun streamsContentAndReasoning() = runBlocking {
        val sse = listOf(
            """{"choices":[{"delta":{"role":"assistant"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"think "}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"hard"}}]}""",
            """{"choices":[{"delta":{"content":"Hel"}}]}""",
            """{"choices":[{"delta":{"content":"lo"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5}}""",
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))

        val deltas = OpenAiClient(base() + "/", "").streamChat(
            "m", listOf(ChatMessage("user", "hi")), 0.5f,
        ).toList()

        assertEquals("Hello", deltas.joinToString("") { it.content })
        assertEquals("think hard", deltas.joinToString("") { it.reasoning })
        assertEquals(net.daniellehmann.localchat.api.Usage(12, 5), deltas.last().usage)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals(null, req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"stream\":true"))
        assertTrue(body, body.contains("\"model\":\"m\""))
        assertTrue(body, body.contains("\"include_usage\":true"))
        assertTrue(body, body.contains("\"role\":\"user\",\"content\":\"hi\""))
    }

    @Test
    fun surfacesServerErrorMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"model not found","type":"invalid_request_error"}}"""),
        )
        try {
            OpenAiClient(base(), "").streamChat("m", emptyList(), 1f).toList()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("HTTP 404: model not found", e.message)
        }
    }
}
