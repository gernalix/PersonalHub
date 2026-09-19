# PersonalHub alerts

## Ownership

`:core:alerts` contains the shared alert evaluator and notification tap policy.

Timer and Places share alert semantics but **not tag data**:

- Timer rules use Timer tag IDs from the existing Timer snapshot model.
- Places rules use `place_tags` and `place_tag_cross_ref` in `personalhub.db`.
- Equal numeric IDs across the two domains are unrelated. The evaluator checks the domain before matching tags.

Timer keeps its existing rule persistence for compatibility. Places rules are stored in `alert_rules` and tag targets in `alert_place_tag_targets`.

## Places triggers

Places alerts are evaluated only after a user-initiated check-in or check-out has been committed successfully.

Supported triggers:

- `PLACE_CHECK_IN`
- `PLACE_CHECK_OUT`
- `PLACE_BOTH`

Supported targets:

- one exact place UUID;
- a set of Places tags matched with `ALL` or `ANY`.

This feature never requests location in the background and is independent from the platform geofence feature already present in Places. Android geofence ENTER/EXIT events do not fire these manual check-in/out alerts.

## Notification links

After trimming whitespace, if the entire alert message is one supported URI, tapping the notification opens that URI directly.

Allowed schemes:

- `https`
- `http`
- `workflowy`

Workflowy links are explicitly routed to the Workflowy package when it can handle the URI. Unsafe or privileged schemes such as `intent:`, `file:` and `content:` are not auto-opened. If a message contains any additional text, the normal PersonalHub alert destination is used instead.

Android can still show its own app chooser for a generic URL when the user has no default handler. PersonalHub does not add an intermediate confirmation screen.

## Tasker bridge

Every matched Timer alert and every successfully posted Places alert can emit an explicit broadcast to Tasker:

`com.gernalix.personalhub.ALERT_FIRED`

The intent is restricted to package `net.dinglisch.android.taskerm`; unrelated applications cannot receive it as a generic broadcast.

Extras:

- `rule_id`
- `domain`
- `trigger`
- `entity_id`
- `tag_ids`
- `tags`
- `title`
- `message`
- `fired_at_ms`

Tasker is not a dependency of PersonalHub. Without a Tasker profile listening for the action, the broadcast has no effect.
