package com.gernalix.personalhub.core.database.capsules.gitdata

import android.content.Context

data class GitReadOnlyArtifactHead(
    val branch: String,
    val revision: String,
)

data class GitReadOnlyArtifact(
    val branch: String,
    val revision: String,
    val bytes: ByteArray,
)

/**
 * Narrow pull-only GitHub artifact surface for feature consumers that must reuse the existing
 * encrypted PersonalHub Git credential without inheriting GitDataSync's bidirectional semantics.
 */
object GitReadOnlyArtifactClient {
    fun tokenAvailable(context: Context): Boolean =
        runCatching { GitDataSettings.token(context.applicationContext).isNotBlank() }
            .getOrDefault(false)

    fun remoteHead(
        context: Context,
        repositoryUrl: String,
    ): GitReadOnlyArtifactHead {
        val transport = transport(context, repositoryUrl)
        val head = transport.remoteHead()
        return GitReadOnlyArtifactHead(
            branch = head.branch,
            revision = head.commitSha,
        )
    }

    fun fetch(
        context: Context,
        repositoryUrl: String,
        path: String,
        revision: String? = null,
    ): GitReadOnlyArtifact {
        require(path.isNotBlank() && !path.startsWith("/") && ".." !in path.split('/')) {
            "Invalid Git artifact path"
        }
        val transport = transport(context, repositoryUrl)
        val head = transport.remoteHead()
        val ref = revision?.trim()?.takeIf(String::isNotEmpty) ?: head.commitSha
        val bytes = transport.readFile(path, ref)
        return GitReadOnlyArtifact(
            branch = head.branch,
            revision = ref,
            bytes = bytes,
        )
    }

    private fun transport(
        context: Context,
        repositoryUrl: String,
    ): GitHubDataTransport {
        val repository = requireNotNull(GitRepository.parse(repositoryUrl)) {
            "Use an HTTPS GitHub repository URL"
        }
        val token = GitDataSettings.token(context.applicationContext)
        require(token.isNotBlank()) {
            "Configure the existing PersonalHub GitHub token before using read-only Git artifacts"
        }
        return GitHubDataTransport(repository, token)
    }
}
