package com.gernalix.personalhub.core.database.capsules.gitdata

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.ArrayDeque
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitHubDataTransportTest {
    @Test
    fun unbornRepositoryCreatesInitialBranchWithoutParents() = api(
        action("GET", "/repos/owner/data", 200, "{\"default_branch\":\"main\"}"),
        action("GET", "/repos/owner/data/git/ref/heads/main", 404),
        action("POST", "/repos/owner/data/git/blobs", 201, "{\"sha\":\"blob\"}"),
        action("POST", "/repos/owner/data/git/trees", 201, "{\"sha\":\"tree\"}") {
            assertFalse(JSONObject(it).has("base_tree"))
        },
        action("POST", "/repos/owner/data/git/commits", 201, "{\"sha\":\"initial\"}") {
            assertEquals(0, JSONObject(it).getJSONArray("parents").length())
        },
        action("POST", "/repos/owner/data/git/refs", 201, "{}") {
            assertEquals("refs/heads/main", JSONObject(it).getString("ref"))
        },
    ) { transport ->
        assertEquals("initial", transport.pushFiles(mapOf("state.json" to byteArrayOf(1)), "Initial sync"))
    }

    @Test
    fun initializedRepositoryPreservesTheNormalPushCommit() = api(
        action("GET", "/repos/owner/data", 200, "{\"default_branch\":\"main\"}"),
        action("GET", "/repos/owner/data/git/ref/heads/main", 200, "{\"object\":{\"sha\":\"old\"}}"),
        action("GET", "/repos/owner/data/git/commits/old", 200, "{\"tree\":{\"sha\":\"old-tree\"}}"),
        action("POST", "/repos/owner/data/git/blobs", 201, "{\"sha\":\"blob\"}"),
        action("POST", "/repos/owner/data/git/trees", 201, "{\"sha\":\"tree\"}") {
            assertEquals("old-tree", JSONObject(it).getString("base_tree"))
        },
        action("POST", "/repos/owner/data/git/commits", 201, "{\"sha\":\"new\"}") {
            assertEquals("old", JSONObject(it).getJSONArray("parents").getString(0))
        },
    ) { transport ->
        // The JDK HTTP implementation used by unit tests rejects PATCH before opening a socket.
        // Android's HttpURLConnection supports it; all preceding normal-push requests are verified here.
        assertTrue(runCatching {
            transport.pushFiles(mapOf("state.json" to byteArrayOf(1)), "Sync")
        }.exceptionOrNull() is java.net.ProtocolException)
    }

    @Test
    fun authenticationFailureIsNotTreatedAsAnUnbornRepository() = api(
        action("GET", "/repos/owner/data", 401, "{\"message\":\"Bad credentials\"}"),
    ) { transport ->
        val failure = runCatching { transport.validatePrivateWritable() }.exceptionOrNull()
        assertTrue(failure?.message?.contains("401") == true)
    }

    @Test
    fun concurrentInitializationFallsBackToTheCreatedHead() = api(
        action("GET", "/repos/owner/data", 200, "{\"default_branch\":\"main\"}"),
        action("GET", "/repos/owner/data/git/ref/heads/main", 404),
        action("POST", "/repos/owner/data/git/blobs", 201, "{\"sha\":\"blob-1\"}"),
        action("POST", "/repos/owner/data/git/trees", 201, "{\"sha\":\"tree-1\"}"),
        action("POST", "/repos/owner/data/git/commits", 201, "{\"sha\":\"lost-race\"}"),
        action("POST", "/repos/owner/data/git/refs", 422, "{\"message\":\"Reference already exists\"}"),
        action("GET", "/repos/owner/data", 200, "{\"default_branch\":\"main\"}"),
        action("GET", "/repos/owner/data/git/ref/heads/main", 200, "{\"object\":{\"sha\":\"other\"}}"),
        action("GET", "/repos/owner/data/git/commits/other", 200, "{\"tree\":{\"sha\":\"other-tree\"}}"),
        action("POST", "/repos/owner/data/git/blobs", 201, "{\"sha\":\"blob-2\"}"),
        action("POST", "/repos/owner/data/git/trees", 201, "{\"sha\":\"tree-2\"}"),
        action("POST", "/repos/owner/data/git/commits", 201, "{\"sha\":\"merged\"}"),
    ) { transport ->
        assertTrue(runCatching {
            transport.pushFiles(mapOf("state.json" to byteArrayOf(1)), "Sync")
        }.exceptionOrNull() is java.net.ProtocolException)
    }

    private fun api(vararg actions: Action, block: (GitHubDataTransport) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pending = ArrayDeque(actions.asList())
        server.createContext("/") { exchange -> respond(exchange, pending.removeFirst()) }
        server.start()
        try {
            block(GitHubDataTransport(GitRepository("owner", "data"), "token", "http://127.0.0.1:${server.address.port}/repos"))
            assertTrue("Unexpected requests remain: ${pending.size}", pending.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, action: Action) {
        assertEquals(action.method, exchange.requestMethod)
        assertEquals(action.path, exchange.requestURI.path)
        action.verify(String(exchange.requestBody.readBytes(), Charsets.UTF_8))
        val bytes = action.response.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(action.code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun action(
        method: String,
        path: String,
        code: Int,
        response: String = "",
        verify: (String) -> Unit = {},
    ) = Action(method, path, code, response, verify)

    private data class Action(
        val method: String,
        val path: String,
        val code: Int,
        val response: String,
        val verify: (String) -> Unit,
    )
}
