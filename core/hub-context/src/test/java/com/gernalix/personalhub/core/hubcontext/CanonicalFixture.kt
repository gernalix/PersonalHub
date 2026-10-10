package com.gernalix.personalhub.core.hubcontext

import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.database.PersonalHubDatabase

/** Fake adapters own fixture objects outside Room's physical domain tables. */
internal fun canonicalFixture(database: PersonalHubDatabase, module: String, kind: String, id: String): HubEntityRef {
    database.openHelper.writableDatabase.execSQL(
        "INSERT INTO hub_entities(canonical_id,entity_kind,owning_module,local_table,local_key,lifecycle,created_at,updated_at) " +
            "SELECT ?,?,?,?,?,'ACTIVE',1,1 WHERE NOT EXISTS(SELECT 1 FROM hub_entities WHERE canonical_id=?)",
        arrayOf(id,"$module/$kind",module,"test-fixture.$module.$kind",id,id),
    )
    return HubEntityRef(module,kind,id)
}
