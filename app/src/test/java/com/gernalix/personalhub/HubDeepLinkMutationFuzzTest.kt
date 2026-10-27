package com.gernalix.personalhub

import android.net.Uri
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HubDeepLinkMutationFuzzTest {
    @Test fun fourThousandMutatedExternalUrisNeverCrashParser() {
        val seeds=listOf(
            "personalhub://entity/v1/people/person/a?action=edit",
            "personalhub://since_when/v1/create?source=%7B%7D",
            "personalhub://search/v1?module=places&scope_module=people",
            "personalhub://context/v1/hello",
            "personalhub://event/v1/hello",
            "personalhub://entity/v1/a/b/c?action=delete",
            "personalhub://search/v1?from=invalid&to=invalid",
            "personalhub://entity/v1/a/b/c?action=view&action=edit")
        val r=Random(20261027)
        val alphabet="ab12ef%?&=/# +!$()[]{}:;_-"
        repeat(4_000) { i ->
            val seed=seeds[i%seeds.size]
            val input=buildString {
                append(seed.take(r.nextInt(seed.length+1)))
                repeat(r.nextInt(0,140)) { append(alphabet[r.nextInt(alphabet.length)]) }
            }
            try {
                assertNotNull(HubDeepLinkContract.parse(Uri.parse(input)))
            } catch (error: Throwable) {
                throw AssertionError("Parser failure seed=20261027 sample=$i uri="+input.take(180),error)
            }
        }
    }
    @Test fun dangerousAndDuplicateActionsAreRejected() {
        for (uri in listOf(
            "personalhub://entity/v1/people/person/42?action=delete",
            "personalhub://entity/v1/people/person/42?action=edit&action=view")) {
            assertNull(HubDeepLinkContract.parse(Uri.parse(uri)).target)
        }
    }
}
