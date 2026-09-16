# PersonalHub Hub utilities — user guide

This document describes the current behavior of the three cross-module tools exposed from PersonalHub Home and the current meaning of WordPulse fatigue scores.

## Context

`Context` creates a saved relationship between entities that live in different modules: for example a Timer session, a person and a place. A context needs at least two members.

The composer can also detect entities that overlap a selected time window and can preselect/suggest a nearby place when location data is available. Saving the composer persists the selected entity references as a Hub context.

Current limitation: Timer session labels are resolved from the session title and fall back to a backend-oriented session label when the title is empty. Tag names are not part of the Hub summary. This makes untitled Timer sessions hard to understand in Context and is a known UX defect.

## Search

`Search` is a temporal search across the modules that expose time-aware records. Choose a from/to interval and optionally restrict the modules. Results are merged and grouped by module.

Search is also the current entry point for creating an **episode**: enter selection mode, select at least two entities/results, give the selection a title, and save it.

## Episodes

An episode is currently **not a separate persistence type**. `Save episode` stores the selected entity references and title through the same Hub-context persistence used by Context.

Consequently there is currently no dedicated global `Episodes` browser from which every saved episode can be listed and reopened by title. A saved context can surface through the relationships of its member entities, but the current recall path is not sufficiently discoverable. A dedicated saved-episodes recall surface is therefore a product requirement, not user error.

## Activity

`Activity` is the global change/audit register. It lists recorded actions across modules, can filter by module and system events, opens the related entity when a navigation target exists, exposes `Undo` for reversible active entries, and can copy a deep link to an event.

It is different from Timer sessions: `Activity` answers “what changed in PersonalHub?”, while `Search` answers “what happened in this time interval?” and `Context` answers “which entities belong together?”.

## WordPulse fatigue score

The fatigue score is a **personalized 0–100 deviation signal**, not a percentage of biological tiredness and not a medical measurement. Higher means the current typing behavior looks more fatigue-like relative to the user's own baseline; lower means it is closer to baseline.

The current model combines available domains and renormalizes the weights when some inputs are missing:

- typing speed: 35%
- rhythm/pauses: 30%
- within-session drift: 15%
- sleep context, when available: 12%
- control/corrections: 8%

The score uses personal baseline distributions and only penalizes deterioration relative to baseline. There are no canonical clinical bands such as “0–25 rested / 75–100 exhausted”, so the useful interpretation is **relative and longitudinal**: compare the score with the user's own usual values, recent trend, sleep context and performance rather than treating one number as a diagnosis.

When sufficient PVT calibration data exist, calibration can adjust the raw typing-derived estimate; otherwise the raw personalized score is used.
