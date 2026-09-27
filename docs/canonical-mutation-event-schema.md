Per PH userei un **Mutation Event Schema unico**, indipendente dal modulo, con payload strutturato e semanticamente stabile.

## Schema canonico

```json
{
  "event_id": "01K...",
  "occurred_at": "2026-09-27T19:34:21.482+02:00",

  "transaction_id": "01K...",
  "sequence": 1,

  "module": "timer",
  "event_type": "timer.session.updated",

  "entity": {
    "type": "session",
    "id": "session_123"
  },

  "actor": {
    "type": "user",
    "source": "personalhub"
  },

  "before": {},
  "after": {},

  "context": {},
  "schema_version": 1
}
```

### Campi obbligatori

| Campo | Significato |
|---|---|
| `event_id` | ID globale e immutabile dell'evento |
| `occurred_at` | timestamp reale della mutazione |
| `transaction_id` | raggruppa tutte le mutazioni appartenenti alla stessa azione logica |
| `sequence` | ordine degli eventi dentro la transaction |
| `module` | `timer`, `places`, `people`, `substances`, `money`, `workflowy`, ecc. |
| `event_type` | nome semantico stabile dell'evento |
| `entity.type` | tipo dell'entità principale |
| `entity.id` | ID stabile dell'entità |
| `actor` | chi/cosa ha causato la modifica |
| `before` | stato precedente rilevante |
| `after` | stato successivo rilevante |
| `schema_version` | versione dello schema dell'evento |

`context` può essere `{}` ma il campo dovrebbe esistere sempre.

---

# `event_type`

Formato:

```text
<domain>.<entity>.<action>
```

Esempi:

```text
timer.session.created
timer.session.updated
timer.session.deleted

tags.tag.created
tags.tag.renamed
tags.tag.deleted

places.place.updated
places.checkin.created

workflowy.link.created
workflowy.link.deleted

substances.intake.created

money.transaction.updated
```

Meglio evitare eventi generici come:

```text
row.updated
database.changed
entity.modified
```

perché costringerebbero History a ricostruire il significato.

---

# Regola fondamentale per `before` / `after`

**Registrare solo lo stato semanticamente rilevante per quell'evento.**

Non necessariamente l'intera riga DB.

### Update

Esempio: sessione Timer rinominata e durata modificata.

```json
{
  "event_type": "timer.session.updated",
  "before": {
    "title": "Walk",
    "duration_seconds": 1800
  },
  "after": {
    "title": "Long walk",
    "duration_seconds": 2700
  }
}
```

Non includerei:

```json
{
  "updated_at": "...",
  "internal_revision": 18,
  "sync_dirty": true
}
```

se sono dettagli tecnici.

### Regola: solo campi cambiati

Per gli update, `before` e `after` dovrebbero contenere **soltanto i campi semanticamente modificati**.

Quindi:

```json
"before": {
  "title": "Walk"
},
"after": {
  "title": "Long walk"
}
```

è preferibile a serializzare tutta la sessione.

Questo rende banale produrre:

> Sessione rinominata da “Walk” a “Long walk”.

---

# Create

Per una creazione:

```json
{
  "before": null,
  "after": {
    "name": "Work",
    "parent_id": null
  }
}
```

Esempio:

```json
{
  "event_type": "tags.tag.created",
  "entity": {
    "type": "tag",
    "id": "tag_91"
  },
  "before": null,
  "after": {
    "name": "Work"
  }
}
```

---

# Delete

Per una cancellazione:

```json
{
  "before": {
    "name": "Work"
  },
  "after": null
}
```

È importante conservare abbastanza informazioni in `before` da poter mostrare History **anche quando l'entità non esiste più**.

Non affidarsi quindi a:

```text
entity_id → lookup corrente
```

per costruire la descrizione.

Se elimini una chain, History deve poter dire ancora:

> Eliminata chain “Morning routine”.

---

# Relazioni

Le associazioni vanno trattate come azioni semantiche, non come insert/delete delle join table.

Esempio:

```json
{
  "event_type": "timer.session.tag_added",
  "entity": {
    "type": "session",
    "id": "session_123"
  },
  "before": null,
  "after": {
    "tag": {
      "id": "tag_7",
      "name": "Exercise"
    }
  }
}
```

Non:

```text
session_tag row inserted
```

History potrà quindi mostrare:

> Aggiunto il tag “Exercise” alla sessione “Morning walk”.

---

# `transaction_id`

Questo è probabilmente il campo più importante dopo `event_type`.

Una **transaction logica utente = un solo `transaction_id`**.

Supponiamo che l'utente crei una sessione e contemporaneamente:

- assegni due tag;
- colleghi Workflowy;
- imposti una nota.

Tecnicamente possono esserci molte scritture.

Tutte ricevono:

```text
transaction_id = TX123
```

con:

```text
sequence = 1
sequence = 2
sequence = 3
sequence = 4
```

Per esempio:

```text
TX123 / 1 timer.session.created
TX123 / 2 timer.session.tag_added
TX123 / 3 timer.session.tag_added
TX123 / 4 workflowy.link.created
```

History può poi decidere di renderle come una sola entry:

> Creata sessione “Gym”, con tag Exercise e Health e collegamento Workflowy.

### Regola precisa

Un nuovo `transaction_id` viene creato quando inizia una **nuova intenzione utente o operazione applicativa atomica**.

Non quando inizia semplicemente una query SQLite.

---

# Transaction annidate

Non genererei nuovi transaction ID per funzioni interne.

Esempio:

```text
saveSession()
  ├─ updateSession()
  ├─ replaceTags()
  └─ updateWorkflowyLink()
```

Tutti devono ricevere lo stesso transaction context.

Concettualmente:

```python
with mutation_transaction() as tx:
    update_session(tx)
    replace_tags(tx)
    update_workflowy_link(tx)
```

---

# Operazioni automatiche

Anche queste possono essere registrate, ma distinguendo l'attore.

```json
"actor": {
  "type": "system",
  "source": "migration"
}
```

oppure:

```json
"actor": {
  "type": "system",
  "source": "sync"
}
```

Tipi che suggerirei:

```text
user
system
import
migration
sync
automation
```

Questo permette a History di nascondere normalmente rumore tecnico, pur mantenendolo nell'audit completo.

---

# `context`

Solo informazioni supplementari utili, non il contenuto principale dell'evento.

Esempio:

```json
"context": {
  "screen": "timer_now",
  "operation": "edit_session"
}
```

oppure:

```json
"context": {
  "import_id": "import_123"
}
```

Non dovrebbe diventare un deposito di campi casuali.

---

# Snapshot dei nomi

Per entità referenziate suggerisco quasi sempre di salvare sia ID sia label al momento dell'evento:

```json
{
  "tag": {
    "id": "tag_7",
    "name": "Exercise"
  }
}
```

Questo è importante perché il tag potrebbe poi essere rinominato.

History dovrebbe dire cosa è successo **all'epoca**, non reinterpretare il passato usando lo stato attuale.

---

# Esempi completi

### Tag rinominato

```json
{
  "event_id": "EV1",
  "occurred_at": "2026-09-27T19:40:00+02:00",
  "transaction_id": "TX1",
  "sequence": 1,

  "module": "tags",
  "event_type": "tags.tag.renamed",

  "entity": {
    "type": "tag",
    "id": "tag_44"
  },

  "actor": {
    "type": "user",
    "source": "personalhub"
  },

  "before": {
    "name": "Gym"
  },

  "after": {
    "name": "Exercise"
  },

  "context": {},
  "schema_version": 1
}
```

History:

> Rinominato tag “Gym” → “Exercise”.

---

### Sessione modificata

```json
{
  "event_type": "timer.session.updated",

  "entity": {
    "type": "session",
    "id": "session_98"
  },

  "before": {
    "started_at": "18:00",
    "ended_at": "18:30"
  },

  "after": {
    "started_at": "17:45",
    "ended_at": "18:42"
  }
}
```

History:

> Modificata sessione “Walk”: 18:00–18:30 → 17:45–18:42.

---

### Chain eliminata

```json
{
  "event_type": "timer.chain.deleted",

  "entity": {
    "type": "chain",
    "id": "chain_32"
  },

  "before": {
    "name": "Morning routine",
    "session_count": 4
  },

  "after": null
}
```

History:

> Eliminata chain “Morning routine” con 4 sessioni.

---

# Cosa NON registrerei

Non produrrei eventi History-level per:

```text
updated_at changed
row_version incremented
cache invalidated
usage_count recalculated
foreign-key row inserted
position normalized
sync state changed
```

a meno che quel cambiamento rappresenti direttamente qualcosa che interessa l'utente.

Si può comunque mantenere un log tecnico separato se serve al debugging.

---

# Struttura DB

Una tabella centrale potrebbe essere:

```sql
mutation_events (
    event_id TEXT PRIMARY KEY,
    occurred_at INTEGER NOT NULL,

    transaction_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,

    module TEXT NOT NULL,
    event_type TEXT NOT NULL,

    entity_type TEXT NOT NULL,
    entity_id TEXT,

    actor_type TEXT NOT NULL,
    actor_source TEXT,

    before_json TEXT,
    after_json TEXT,
    context_json TEXT NOT NULL DEFAULT '{}',

    schema_version INTEGER NOT NULL,

    UNIQUE(transaction_id, sequence)
);
```

Indici minimi:

```sql
INDEX occurred_at
INDEX transaction_id
INDEX event_type
INDEX (entity_type, entity_id)
INDEX module
```

## Principio finale

Separerei chiaramente:

```text
Mutation Event Store
        │
        ├── History
        ├── Audit
        ├── Undo
        ├── Debugging
        └── eventuale sync
```

La **History non dovrebbe avere un proprio modello degli eventi**.

Deve diventare essenzialmente:

```text
MutationEvent
    ↓
group / filter
    ↓
user-friendly formatter
```

Questo eliminerebbe alla radice gran parte della complessità che stai incontrando ora con la reimplementazione di History.

Per portarlo nel codice

- Definisci il catalogo iniziale degli eventi
- Progetta il Mutation Event Engine
