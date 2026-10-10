package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.capsules.settings.GitPatchReviewActions
import com.gernalix.personalhub.capsules.settings.GitPatchReviewPanel
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.history.HistoryQueryCapsule
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSync
import com.gernalix.personalhub.core.database.capsules.gitdata.GitPatchPreview
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventDraft
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore
import com.gernalix.sostanze.data.IntakeEventEntity
import com.gernalix.sostanze.data.SubstanceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic, append-only fixtures in .qa. Production data and package are never touched. */
@RunWith(AndroidJUnit4::class)
class HistoryAndPatchReviewDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun waitLoaded(count:Int) {
        try { compose.waitUntil(15_000) { compose.onAllNodesWithTag("history-loaded-count").fetchSemanticsNodes().any { node ->
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { it.text.contains(count.toString()) }
        } } } catch(error:Exception) { compose.onRoot().printToLog("History193828");throw error }
    }
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }

    @Test fun scopedAndGlobalRecordsFindOldIntakeAndOpenExactCanonicalRegistration() {
        check(context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        val previousZone=TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Copenhagen"))
        val db=PersonalHubDatabase.get(context)
        val suffix=System.nanoTime().toString()
        val label="Sostanza QA $suffix"
        val latest=java.time.Instant.parse("2026-10-07T14:57:28.450Z").toEpochMilli()
        val substance=runBlocking { db.dao().insertSubstance(SubstanceEntity(name=label,canonicalName=label,type="farmaco",stockCurrent=1000.0,stockUnit="mg",dosePerIntake=1.0,doseUnit="mg",dailyFrequency=1,startEpochDay=1)) }
        runBlocking { db.withTransaction {
            repeat(719) { i -> db.dao().insertIntake(IntakeEventEntity(substanceId=substance,timestampMs=latest-i*1000,timestampUtc=latest-i*1000,dose=1.0,doseUnit="mg")) }
            repeat(1100) { i -> MutationEventStore.append(db.openHelper.writableDatabase,MutationEventDraft(
                transactionId="noise-$suffix-$i",module="timer",eventType="timer.session.created",entityType="session",entityId="noise-$i",
                occurredAt=latest+10_000+i,afterJson="{\"title\":\"Later session\"}")) }
        } }
        assertEquals(50,HistoryQueryCapsule.records(db.openHelper.readableDatabase,HistoryQueryCapsule.Filter(modules=setOf("substances"),query=label)).groups.size)
        val latestRow=runBlocking { db.dao().lastIntakeFor(substance) }!!
        initializeHubContextRuntime(context)
        val scopedUri=HubDeepLinkContract.searchUri(modules=listOf("substances"),scopeModuleId="substances",query=label)
        val scenario=ActivityScenario.launch<HubDeepLinkActivity>(Intent(context,HubDeepLinkActivity::class.java).setData(scopedUri))
        try {
            waitFor("history-mode-records")
            compose.onNodeWithTag("history-module-filter").assertDoesNotExist()
            compose.onNodeWithTag("history-mode-records").performClick()
            waitLoaded(50)
            compose.onNodeWithTag("history-results").performScrollToNode(hasTestTag("history-more"))
            compose.onNodeWithTag("history-more").performClick()
            waitLoaded(100)
            scenario.recreate()
            waitFor("history-mode-records")
            compose.onNodeWithTag("history-mode-records").performClick()
            waitLoaded(50)
            compose.onNodeWithTag("history-undo").assertDoesNotExist()
            val recordTag="history-row-record:${latestRow.canonicalId}"
            compose.onNodeWithTag("history-results").performScrollToNode(hasTestTag(recordTag))
            compose.onNodeWithTag(recordTag).performClick()
            waitFor("history-open")
            compose.onNodeWithTag("history-open").performClick()
            waitFor("intake-detail-time")
            compose.onNodeWithTag("intake-detail-time").assertTextContains("16:57",substring=true)
            assertEquals(latest,latestRow.timestampUtc)
            val target=runBlocking { com.gernalix.personalhub.core.hubcontext.HubContextRuntime.adapter("substances","intake").openTarget(latestRow.canonicalId) }!!
            assertEquals(latestRow.canonicalId,android.net.Uri.parse(target.uri).getQueryParameter("intakeId"))
            val device=androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
            device.takeScreenshot(java.io.File(context.cacheDir,"193828-intake.png"))
            device.pressBack(); device.pressBack()
        } finally { scenario.close(); TimeZone.setDefault(previousZone) }
        val globalScenario=ActivityScenario.launch<HubDeepLinkActivity>(Intent(context,HubDeepLinkActivity::class.java).setData(HubDeepLinkContract.searchUri(query=label)))
        try {
            waitFor("history-mode-records")
            compose.onNodeWithTag("history-module-filter").assertExists()
            compose.onNodeWithTag("history-mode-records").performClick()
            waitLoaded(50)
            compose.onAllNodes(hasText(label,substring=true)).onFirst().assertExists()
        } finally { globalScenario.close() }
        val global=HistoryQueryCapsule.records(db.openHelper.readableDatabase,HistoryQueryCapsule.Filter(query=label))
        val scoped=HistoryQueryCapsule.records(db.openHelper.readableDatabase,HistoryQueryCapsule.Filter(modules=setOf("substances"),query=label))
        assertEquals(global.groups,scoped.groups)
        val seen=mutableSetOf<String>()
        var cursor: HistoryQueryCapsule.Cursor?=null
        do {
            val page=HistoryQueryCapsule.records(db.openHelper.readableDatabase,HistoryQueryCapsule.Filter(query=label),cursor)
            page.groups.forEach { assertTrue(seen.add(it.groupId)) }
            cursor=page.next
        } while(cursor!=null)
        assertEquals(719,seen.size)
    }

    @Test fun supportedUndoRestoresSyntheticPlaceAndRecordsCompensation() {
        check(context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        check(!com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSettings.configuration(context).enabled)
        val db=PersonalHubDatabase.get(context)
        val sql=db.openHelper.writableDatabase
        val id=java.util.UUID.randomUUID().toString()
        val label="Undo place QA ${System.nanoTime()}"
        sql.execSQL("INSERT INTO places(uuid,nickname,address,lat,lon,radius_m,notes,source_app,created_at,updated_at,archived,first_check_in_at_place) VALUES(?,?,NULL,NULL,NULL,NULL,NULL,'test',1,1,0,NULL)",arrayOf(id,label))
        sql.execSQL("UPDATE places SET nickname=?,updated_at=2 WHERE uuid=?",arrayOf("$label updated",id))
        val activity=runBlocking { db.activityDao().page("places",1,null,null,50).first { it.entityId==id && it.action=="place_updated" } }
        val group=requireNotNull(activity.groupId)
        assertEquals(1,MutationEventStore.byTransaction(sql,group).size)
        assertEquals(com.gernalix.personalhub.core.database.capsules.mutationevents.MutationUndoTarget.Activity(activity.id),
            com.gernalix.personalhub.core.database.capsules.mutationevents.MutationUndoResolver.resolve(sql,group,false))
        assertEquals(1,HistoryQueryCapsule.page(sql,HistoryQueryCapsule.Filter(modules=setOf("places"),query="$label updated")).groups.size)
        initializeHubContextRuntime(context)
        val scenario=ActivityScenario.launch<HubDeepLinkActivity>(Intent(context,HubDeepLinkActivity::class.java)
            .setData(HubDeepLinkContract.searchUri(modules=listOf("places"),scopeModuleId="places",query="$label updated")))
        try {
            waitFor("history-mode-mutations")
            compose.onNodeWithTag("history-mode-mutations").performClick()
            waitLoaded(1)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("history-undo").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("history-undo").onFirst().assertIsEnabled().performClick()
            compose.waitUntil(15_000) { sql.query("SELECT nickname FROM places WHERE uuid=?",arrayOf(id)).use { it.moveToFirst() && it.getString(0)==label } }
            val original=runBlocking { db.activityDao().byId(activity.id) }!!
            assertEquals(com.gernalix.personalhub.core.database.HubActivityStatus.REVERTED,original.status)
            assertNotNull(original.revertedAt)
            assertTrue(runBlocking { db.activityDao().page("places",1,null,null,50).any { it.revertsActivityId==activity.id && !it.reversible } })
        } finally { scenario.close() }
    }

    @Test fun patchPreviewFailureAndExplicitConfirmationGateApplication() {
        check(context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        PersonalHubDatabase.get(context).openHelper.writableDatabase
        val actions=RecordingActions()
        val scenario=ActivityScenario.launch<DatabaseActivity>(Intent(context,DatabaseActivity::class.java))
        try {
            scenario.onActivity { activity -> activity.setContent {
                var applied by remember { mutableStateOf(false) }
                if(applied) Text("Applied fixture") else GitPatchReviewPanel("fixture-patch","moving-branch",onBack={},onApplied={ applied=true },actions=actions)
            } }
            waitFor("git-patch-preview")
            compose.onNodeWithTag("git-patch-apply").assertDoesNotExist()
            compose.onNodeWithTag("git-patch-preview").performClick()
            waitFor("git-patch-failed")
            compose.onNodeWithTag("git-patch-apply").assertDoesNotExist()
            actions.fail=false
            compose.onNodeWithTag("git-patch-preview").performClick()
            waitFor("git-patch-pass")
            compose.onNodeWithTag("git-patch-apply").performClick()
            waitFor("git-patch-confirm")
            assertEquals(0,actions.applies.get())
            compose.onNodeWithText(context.getString(com.gernalix.personalhub.core.ui.R.string.git_review_cancel)).performClick()
            assertEquals(0,actions.applies.get())
            compose.onNodeWithTag("git-patch-apply").performClick()
            compose.onNodeWithTag("git-patch-confirm").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Applied fixture").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1,actions.applies.get())
            assertEquals("immutable-revision",actions.appliedRef)
            assertEquals("verified-hash",actions.appliedHash)
        } finally { scenario.close() }
    }

    private class RecordingActions: GitPatchReviewActions {
        @Volatile var fail=true
        val applies=AtomicInteger()
        @Volatile var appliedRef:String?=null
        @Volatile var appliedHash:String?=null
        override fun describe(context: Context,ref:String,id:String) = GitDataSync.PatchReview(id,"immutable-revision","verified-hash","Fixture author","Controlled fixture",24,0,1,listOf("finance_accounts"))
        override fun preview(context:Context,ref:String,id:String):GitPatchPreview {
            check(ref=="immutable-revision")
            check(!fail) { "Controlled preview failure" }
            return GitPatchPreview(id,"Fixture author",1,0,1,0,listOf("finance_accounts"),ref,"verified-hash")
        }
        override fun apply(context:Context,ref:String,id:String,hash:String) { appliedRef=ref;appliedHash=hash;applies.incrementAndGet() }
    }
}
