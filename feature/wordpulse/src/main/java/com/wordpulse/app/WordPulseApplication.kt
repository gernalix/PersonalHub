package com.wordpulse.app

import android.app.Application
import com.wordpulse.app.data.SystemTimeProvider
import com.wordpulse.app.data.WordPulseDatabase
import com.wordpulse.app.data.WordRepository

open class WordPulseApplication : Application() {
    val database: WordPulseDatabase by lazy {
        WordPulseDatabase.create(applicationContext)
    }

    val repository: WordRepository by lazy {
        WordRepository(
            database = database,
            timeProvider = SystemTimeProvider,
        )
    }
}
