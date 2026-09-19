package com.gernalix.personalhub.workflowydays

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkflowyDaysTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkflowyDaysStore.replaceAll(context, emptyList())
    }

    @Test
    fun compactFeedParsesSortsAndDerivesStandardDeepLink() {
        val days = WorkflowyDaysFeed.parse(
            """
            {
              "schema_version": 1,
              "generated_at": "2026-09-12T00:00:00Z",
              "workflowy_days": [
                {"date":"2026-09-07","node_id":"2af3dd5c-7b2e-5248-bbee-59d823cea257"},
                {"date":"2026-09-01","node_id":"008338de-f44e-5e25-a6a4-50039aba7fcb"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)), days.map { it.date })
        assertEquals("https://workflowy.com/#/59d823cea257", days.last().deepLink)
    }

    @Test
    fun datasetteArrayShapeIsAccepted() {
        val days = WorkflowyDaysFeed.parse(
            """[{"date":"2026-09-03","node_id":"c4c46cee-ffb6-567b-ab14-595722f50038","imported_at":"2026-09-12T00:00:00Z"}]""",
        )
        assertEquals(LocalDate.of(2026, 9, 3), days.single().date)
    }

    @Test
    fun duplicateDateWithDifferentNodesIsPreservedAndMarkedAmbiguous() {
        val days = WorkflowyDaysFeed.parse(
            """
            {"schema_version":1,"workflowy_days":[
              {"date":"2026-09-07","node_id":"11111111-1111-1111-1111-111111111111"},
              {"date":"2026-09-07","node_id":"22222222-2222-2222-2222-222222222222"}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, days.size)
        WorkflowyDaysStore.replaceAll(context, days)
        assertEquals(2, WorkflowyDaysStore.findAll(context, LocalDate.of(2026, 9, 7)).size)
        assertNull(WorkflowyDaysStore.find(context, LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun replaceAllIsIdempotentAndRemovesDaysNoLongerInFeed() {
        val first = listOf(
            WorkflowyDay(LocalDate.of(2026, 9, 1), "008338de-f44e-5e25-a6a4-50039aba7fcb"),
            WorkflowyDay(LocalDate.of(2026, 9, 7), "2af3dd5c-7b2e-5248-bbee-59d823cea257"),
        )
        WorkflowyDaysStore.replaceAll(context, first)
        WorkflowyDaysStore.replaceAll(context, first)
        assertEquals(2, WorkflowyDaysStore.between(context, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).size)

        val latest = listOf(first.last())
        WorkflowyDaysStore.replaceAll(context, latest)
        assertNull(WorkflowyDaysStore.find(context, LocalDate.of(2026, 9, 1)))
        assertEquals("https://workflowy.com/#/59d823cea257", WorkflowyDaysStore.find(context, LocalDate.of(2026, 9, 7))?.deepLink)
    }
}
