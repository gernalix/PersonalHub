package com.wordpulse.app

import android.content.Context
import com.wordpulse.app.data.SystemTimeProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.wordpulse.app.data.WordRepository

/**
 * Feature-local runtime holder. The host application does not inherit from or expose
 * WordPulse implementation types; the WordPulse Activity resolves its own repository lazily.
 */
internal object WordPulseRuntime {
    @Volatile
    private var repositoryInstance: WordRepository? = null

    fun repository(context: Context): WordRepository {
        repositoryInstance?.let { return it }
        return synchronized(this) {
            repositoryInstance ?: WordRepository(
                database = PersonalHubDatabase.get(context.applicationContext),
                timeProvider = SystemTimeProvider,
            ).also { repositoryInstance = it }
        }
    }
}
