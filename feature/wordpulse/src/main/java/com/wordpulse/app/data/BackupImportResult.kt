package com.wordpulse.app.data

data class BackupImportResult(
    val sessionsImported: Int,
    val wordsImported: Int,
    val correctionsImported: Int = 0,
)
