package com.gernalix.personalhub.core.database.capsules.soldi

import android.content.Context
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.*
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.UUID

/** Explicit operations only; no token storage, credential prompts, background polling or SQLite transport. */
class FinanceGit(private val context: Context) {
    private val prefs = context.getSharedPreferences("soldi_git", Context.MODE_PRIVATE)
    private val exchange get() = FinanceExchange(PersonalHubDatabase.get(context))
    fun url(): String = prefs.getString("url", "")!!
    fun configure(value: String) {
        if (value.isNotBlank()) validateUrl(value, context.packageName.endsWith(".qa"))
        check(prefs.edit().putString("url",value.trim()).commit())
    }
    suspend fun pull() = operate(false)
    suspend fun push() = operate(true)
    private suspend fun operate(push: Boolean) = mutex.withLock { withContext(Dispatchers.IO) {
        val remote = url(); validateUrl(remote,context.packageName.endsWith(".qa"))
        val key = FinanceExchange.hash(remote)
        val dir = File(context.noBackupFilesDir,"soldi-git-${UUID.randomUUID()}")
        try {
            Git.init().setDirectory(dir).setInitialBranch("exchange").call().use { git ->
                git.repository.config.apply { setString("remote","origin","url",remote); setString("http",null,"followRedirects","false"); save() }
                val refs = Git.lsRemoteRepository().setRemote(remote).setHeads(true).setCredentialsProvider(noCredentials).setTimeout(20).call()
                val head = refs.find { it.name == "refs/heads/soldi" }?.objectId?.name
                if (head != null) {
                    git.fetch().setRemote("origin").setRefSpecs(RefSpec("refs/heads/soldi:refs/remotes/origin/soldi")).setCredentialsProvider(noCredentials).setTimeout(20).call()
                    org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk ->
                        val commit = walk.parseCommit(git.repository.resolve("refs/remotes/origin/soldi"))
                        org.eclipse.jgit.treewalk.TreeWalk(git.repository).use { tree ->
                            tree.addTree(commit.tree)
                            require(tree.next() && tree.pathString == "soldi.json" && tree.getFileMode(0) == org.eclipse.jgit.lib.FileMode.REGULAR_FILE) { "Finance-only branch required" }
                            require(git.repository.open(tree.getObjectId(0)).size <= 2_000_000)
                            require(!tree.next()) { "Finance-only branch required" }
                        }
                    }
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef("refs/remotes/origin/soldi").call()
                    require(git.repository.resolve("HEAD").name == head) { "Remote changed; retry" }
                    val known = prefs.getString("head_$key",null)
                    if(known != null && known != head) org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk ->
                        require(walk.isMergedInto(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(known)),walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(head)))) { "Remote history changed" }
                    }
                }
                val file = File(dir,"soldi.json")
                require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == dir.canonicalFile)
                if (push) {
                    require(head == prefs.getString("head_$key",null)) { "Pull before push" }
                    val text = exchange.export()
                    file.writeText(text)
                    git.add().addFilepattern("soldi.json").call()
                    if (!git.status().call().isClean || head == null) git.commit().setMessage("Update finance interchange").setAuthor("PersonalHub","personalhub@localhost").setCommitter("PersonalHub","personalhub@localhost").call()
                    val results = git.push().setRemote("origin").setRefSpecs(RefSpec("HEAD:refs/heads/soldi")).setCredentialsProvider(noCredentials).setTimeout(20).call()
                    require(results.flatMap { it.remoteUpdates }.all { it.status == RemoteRefUpdate.Status.OK || it.status == RemoteRefUpdate.Status.UP_TO_DATE }) { "Push rejected" }
                    remember(key,git.repository.resolve("HEAD").name,FinanceExchange.fingerprints(text))
                } else {
                    require(head != null && file.isFile && file.length() <= 2_000_000) { "Missing interchange" }
                    val stored = JSONObject(prefs.getString("base_$key","{}")!!)
                    val bases = stored.keys().asSequence().associateWith { stored.getString(it) }
                    val imported = exchange.import(file.readText(),bases)
                    remember(key,head,imported)
                }
            }
        } finally { dir.deleteRecursively() }
    } }
    private fun remember(key: String, head: String, bases: Map<String,String>) {
        check(prefs.edit().putString("head_$key",head).putString("base_$key",JSONObject(bases).toString()).commit())
    }
    companion object {
        private val mutex = Mutex()
        fun validateUrl(value: String, qa: Boolean = false) {
            val uri = URI(value.trim())
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && !uri.host.isNullOrBlank())
            require(uri.scheme == "https" || (qa && uri.scheme == "http" && uri.host in setOf("127.0.0.1","localhost")))
            require(uri.path?.isNotBlank() == true && !uri.rawPath.contains("%"))
        }
        private val noCredentials = object : CredentialsProvider() {
            override fun isInteractive() = false
            override fun supports(vararg items: CredentialItem) = false
            override fun get(uri: URIish, vararg items: CredentialItem) = false
        }
    }
}
