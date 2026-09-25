"""Presentation metadata from PersonalHub's canonical Room schema and Timer serializer.

No second data model: columns and declared relations come from the supplied Room export.
The small explicit mappings cover Timer's existing JSON collections and logical (non-FK) links.
"""
import re

EXCLUDED = {"hub_generation", "hub_sync_pending", "hub_sync_known", "sync_meta", "sync_queue", "sync_shadow"}
TIMER_COLLECTIONS = {
    "tasks": "id name link isRunning totalMs lastStartedAtMs",
    "tags": "id name timedDurationMinutes notificationType isArchived showInTimeline activeChildrenCount totalMs lastStartedAtMs",
    "closedSessions": "sessionId sessionTitle startTs endTs",
    "tagSessions": "tagId tagName sessionId sessionTitle startTs endTs",
    "lifePeriods": "id title description startMs endMs colorArgb displayUnits",
    "timeFenceRules": "id message trigger delivery scope matchMode isEnabled timerMinutes cooldownMs lastFiredAtMs",
    "activeSessionStart": "sessionId startTs",
    "activeTagStart": "sessionId tagId startTs",
    "tagParents": "childId parentId",
    "chains": "id name",
    "chronologySessions": "id title startMs endMs expectedEndMs",
    "runningSessions": "id title startMs endMs expectedEndMs",
    "quickEventTemplates": "id title sortOrder isArchived",
    "quickEventEntries": "id templateId macroId title timestampMs",
    "quickEventFieldDefinitions": "id templateId label type required defaultValue choiceOptions displayOrder",
    "quickEventFieldValues": "id entryId fieldId label type value displayOrder",
    "quickEventMacros": "id title sortOrder isArchived",
    "quickEventMacroActions": "macroId templateId displayOrder",
}


def snake(value):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", value).lower()

