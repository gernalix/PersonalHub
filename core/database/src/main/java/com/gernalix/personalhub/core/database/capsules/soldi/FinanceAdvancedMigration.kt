package com.gernalix.personalhub.core.database.capsules.soldi

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the approved Soldi v2 ledger concepts without reinterpreting existing finance rows. */
class FinanceAdvancedMigration : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE finance_transactions ADD COLUMN personId INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE finance_transactions ADD COLUMN macroId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE finance_transactions ADD COLUMN recurrenceId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE finance_transactions ADD COLUMN occurrenceKey TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE finance_transactions ADD COLUMN reminderAt TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE finance_transactions ADD COLUMN category TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_transactions_personId ON finance_transactions(personId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_transactions_macroId ON finance_transactions(macroId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_transactions_recurrenceId ON finance_transactions(recurrenceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_transactions_recurrenceId_occurrenceKey ON finance_transactions(recurrenceId, occurrenceKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_transactions_reminderAt ON finance_transactions(reminderAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_transactions_category ON finance_transactions(category)")

        db.execSQL("CREATE TABLE IF NOT EXISTS finance_transfers (id TEXT NOT NULL, sourceTransactionId INTEGER NOT NULL, targetTransactionId INTEGER NOT NULL, quotedRate TEXT, feeAmount TEXT, feeCurrency TEXT, createdAt TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sourceTransactionId) REFERENCES finance_transactions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(targetTransactionId) REFERENCES finance_transactions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_finance_transfers_sourceTransactionId ON finance_transfers(sourceTransactionId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_finance_transfers_targetTransactionId ON finance_transfers(targetTransactionId)")

        db.execSQL("CREATE TABLE IF NOT EXISTS finance_macros (id TEXT NOT NULL, title TEXT NOT NULL, accountId TEXT NOT NULL, currency TEXT NOT NULL, occurredAt TEXT NOT NULL, notes TEXT NOT NULL, createdAt TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(accountId) REFERENCES finance_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_macros_accountId ON finance_macros(accountId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_macros_occurredAt ON finance_macros(occurredAt)")

        db.execSQL("CREATE TABLE IF NOT EXISTS finance_recurrences (id TEXT NOT NULL, title TEXT NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL, accountId TEXT NOT NULL, personId INTEGER, chain TEXT NOT NULL, placeId TEXT, notes TEXT NOT NULL, dayOfMonth INTEGER, lastBusinessDay INTEGER NOT NULL, startDate TEXT NOT NULL, endDate TEXT, reminderDaysBefore INTEGER, enabled INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, kind TEXT NOT NULL, targetAccountId TEXT, targetAmount TEXT, quotedRate TEXT, feeAmount TEXT, feeCurrency TEXT, category TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(accountId) REFERENCES finance_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_recurrences_accountId ON finance_recurrences(accountId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_recurrences_targetAccountId ON finance_recurrences(targetAccountId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_recurrences_enabled ON finance_recurrences(enabled)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_recurrences_startDate ON finance_recurrences(startDate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_recurrences_category ON finance_recurrences(category)")

        db.execSQL("CREATE TABLE IF NOT EXISTS finance_recurrence_tags (recurrenceId TEXT NOT NULL, tagId INTEGER NOT NULL, PRIMARY KEY(recurrenceId, tagId), FOREIGN KEY(recurrenceId) REFERENCES finance_recurrences(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(tagId) REFERENCES finance_tags(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_recurrence_tags_tagId ON finance_recurrence_tags(tagId)")

        db.execSQL("CREATE TABLE IF NOT EXISTS finance_recurrence_overrides (recurrenceId TEXT NOT NULL, occurrenceDate TEXT NOT NULL, amount TEXT, targetAmount TEXT, skipped INTEGER NOT NULL, transactionId INTEGER, createdAt TEXT NOT NULL, PRIMARY KEY(recurrenceId, occurrenceDate), FOREIGN KEY(recurrenceId) REFERENCES finance_recurrences(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(transactionId) REFERENCES finance_transactions(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_recurrence_overrides_transactionId ON finance_recurrence_overrides(transactionId)")

        db.execSQL("CREATE TABLE IF NOT EXISTS finance_attachments (id TEXT NOT NULL, transactionId INTEGER NOT NULL, kind TEXT NOT NULL, uri TEXT NOT NULL, title TEXT NOT NULL, mimeType TEXT, createdAt TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(transactionId) REFERENCES finance_transactions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_finance_attachments_transactionId ON finance_attachments(transactionId)")

        db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
    }
}
