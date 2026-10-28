# PersonalHub — audit statico, fuzzing, sicurezza Android e profiling
**Data:** 27 ottobre 2026 · **Sorgente verificata:** `c9aaa634dbe33fb1cb80609d137fd45c7ff01795` (`main`) · **Esecuzione:** GitHub Actions, emulatore effimero Android API 35, `com.gernalix.personalhub.qa`

## Esito sintetico
**Confermato:** errore della ricerca per prossimità attraversando il meridiano ±180° (JUnit negativo, esempio riproducibile); due frame di avvio sul thread UI estremamente lenti sull'emulatore, 354 e 227 ms.

**Confermato da lettura del percorso di codice ma non da scenario Android riprodotto:** possibile salto definitivo degli avvii di backup/sync se non arriva entro 15 s il segnale di schermata Timer utilizzabile; timestamp `createdAt` riscritto nell'update di alias/link di Luoghi.

**Nessun problema osservato** nelle 2 prove di accesso a componenti Android privati, negli 8 input deep link dinamici, nei 4.000 input casuali per il parser deep link, nelle 10.000 stringhe Unicode, nei 30.000 campioni haversine e nei 2.681 tentativi di mutazione con rollback sullo schema SQLite simulato, salvo la regressione geografica separata.

**Limite dell'attestazione:** è stata eseguita una scansione automatizzata di *tutte* le righe di testo tracciate; **non** una revisione umana semantica riga per riga di tutti i 992 file originali. Non è un penetration test esaustivo. Nessuna verifica su Pixel Android 17, profili reali, dati reali, backend bancario o APK finale minificato/firmato.

## Ambito e audit riproducibile
- Originale `main`: 992 file, circa 9,4 MB dei blob tracciati; harness aggiunge 6 file nella prima revisione di audit, per 998 file analizzati.
- 968 file UTF-8, 30 binari/non UTF-8, **200.332 righe** lette da `audit/20261027/source_sqlite_audit.py`. Pattern candidati, *non* conteggi di vulnerabilità: SQL diretto 568, attese sincrone 13, letture integrali 48, raccolta/Flow di liste 140, catch eccezioni 98, rete 34, intent 85. Nessun match regex per noti formati token GitHub/OpenAI/Google; non prova assenza di tutti i segreti.
- SQLite Room v26: **100 tabelle, 240 indici**, `PRAGMA integrity_check=ok`, `foreign_key_check` vuoto prima/dopo **2.681** inserimenti pseudo-casuali in transazione con rollback (132 ammessi, 2.549 respinti). Senza le callback Room e i dati reali.
- JVM Kotlin: `GeoSearchMutationFuzzTest` 3 test, 2 PASS e 1 FAIL (unico fallimento ricercato); `HubDeepLinkMutationFuzzTest` 2 test, 2 PASS. I job Actions sono verdi *perché il passaggio geospaziale era continue-on-error*: non interpretare il verde come PASS di tutti i test.
- CI Android QA API35, single run, 4 cold activity starts, frame stats, PSS, 12s Perfetto, 2 verifiche autorizzazioni e 8 deep link malformati. Questa configurazione non rappresenta i tempi o i permessi del Pixel Android17.

## Vulnerabilità/bug e rimedi in ordine
| Priorità | Cosa emerge | Qualità dell'evidenza | Modifica minima da valutare |
|---|---|---|---|
| P1 | `PostFirstFrameStartup` chiude per sempre l'inizializzazione dopo timeout Timer 15 s: omette autoexport/sync/ripristini già elencati nel blocco successivo. | Percorso di codice certo, scenario timeout non riprodotto | Il fallimento del latch Timer non deve terminare gli altri job; aggiungere test specifico di timeout. |
| P1 | Aggiornare alias/link di Luoghi crea nuova `createdAt=now`. | Percorso di codice certo, non verificato via UI | Conservare `createdAt` dall'entità preesistente ed aggiungere due test su update. |
| P2 | Bounding box non attraversa ±180°, quindi due punti distanti ~111 m vengono separati; search radius 500 m li dovrebbe includere. | **Test JUnit fallito con controesempio riproducibile** | Supportare longitude wrap, una/due regioni SQL; ripetere test esistente. |
| P2 | In Soldi inferenza ONNX e preprocessing `Bitmap.scale/getPixels` sono sincroni e invocati da coroutine della UI senza dispatcher CPU esplicito. | Percorso di codice; non misurato con foto | `withContext(Dispatchers.Default)` o executor dedicato, con test jank sul Pixel. |
| P2 | Decode full resolution `BitmapFactory.decodeByteArray` limita byte compressi ma non bitmap espanse. | Percorso di codice; non fuzzato con immagini ostili | Bounding dimensioni e downsampling `inSampleSize`/pixel cap prima del decode. |
| P2 | Il worker geo ricomincia dal primo luogo con metadati mancanti dopo un errore permanente. | Percorso di codice; non riprodotto contro Nominatim | Registrare cause, retry condizionato e saltare il luogo non recuperabile. |
| P2 | `rememberSaveable` conserva collezioni intere nella ricerca temporale. | Rischio da codice, non osservato | Memorizzare filtri e chiavi al posto degli oggetti serializzati nel Bundle. |
| P2 | Banking `next_page` non ha verifica di monotonicità/ciclo e limite richieste. | Rischio da codice, senza backend test | Max pagine e controllo di avanzamento. |
| P3 | `HubAutoExport` riprogramma lavoro con `REPLACE` a ogni mutazione e produce snapshot completi. | Rischio prestazionale, non misurato su DB reale | Misurare I/O, tempo e interazioni WorkManager prima di cambiarne la durabilità. |
| P3 | `LuoghiHomeViewModel` ricalcola statistiche, visite e filtri al cambiare dello stato. | Rischio prestazionale, non misurato su dataset estesi | Separare i flussi e memorizzare aggregazioni CPU intensive. |

### Link ai percorsi rilevanti
- Avvio: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/app/src/main/java/com/gernalix/personalhub/PersonalHubApplication.kt#L106-L143
- Timer latch: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/feature/multitimetracker/src/main/java/com/example/multitimetracker/api/TimerStartupApi.kt#L9-L15
- Luoghi timestamp: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/feature/luoghi/src/main/java/com/gernalix/luoghi/data/PlaceRepository.kt#L933-L1002
- Longitude: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/places/GeoSearch.kt#L40-L51 e https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/feature/luoghi/src/main/java/com/gernalix/luoghi/provider/PlacesProvider.kt#L241-L270
- Soldi ONNX e decode: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/feature/soldi/src/main/java/com/gernalix/personalhub/soldi/FinancePhotoSemanticEngine.kt#L141-L225
- Soldi UI: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/feature/soldi/src/main/java/com/gernalix/personalhub/soldi/SoldiV2Screen.kt#L101-L178
- Metadati geo: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/geometadata/PlaceGeoMetadataWorker.kt#L27-L48
- Stato salvabile: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/app/src/main/java/com/gernalix/personalhub/HubTemporalSearchScreen.kt#L152-L204
- Banking: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/feature/soldi/src/main/java/com/gernalix/personalhub/soldi/BankingClient.kt#L104-L151
- Autoexport: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/core/database/src/main/java/com/gernalix/personalhub/core/database/HubAutoExport.kt#L13-L101
- Ricalcolo: https://github.com/gernalix/PersonalHub/blob/c9aaa634dbe33fb1cb80609d137fd45c7ff01795/feature/luoghi/src/main/java/com/gernalix/luoghi/LuoghiHomeViewModel.kt#L229-L283

## Profiler: misure realmente raccolte
**Android API 35 emulato**, un campione cold per schermata, APK `.qa`; `am start -W TotalTime`:

| Flow | TotalTime (ms) | PSS dopo avvio (KiB) | Frame rilevati (n) |
|---|---:|---:|---:|
| Home (primo, prima del Perfetto) | 7352 | 211172 | 3 |
| Places | 1132 | 159603 | 2 |
| Timer | 1066 | 159784 | 2 |
| Soldi | 999 | 160005 | 2 |
| Home (ripetuto, nella traccia) | 1031 | non misurato contestualmente | raccolto in Perfetto |

Due o tre frame sono troppo pochi per stimare una percentuale affidabile di jank per schermata.

Perfetto su Home, 12 s: `PH.bg.sinceWhenLegacy` **3092,238 ms wall-clock**, `PH.bg.profileRuntime` **402,706 ms**, `PH.hubRuntimeInit` **64,494 ms**. Il lock di **3072,128 ms** è su **`arch_disk_io_1`**, in attesa di `arch_disk_io_0`; **non è un blocco di 3s del main thread**. Il thread UI aveva frame `Choreographer#doFrame` **354,401 e 226,865 ms**; `OpenDexFilesFromOat` ~218,057 ms; `Compose:initializeView` 70,340 ms e `Compose:recompose` 59,182 ms. Solo uno snapshot senza Pixel e senza sampling CPU di inferenza ONNX. Non dedurre una regressione reale senza confronto con release/Pixel.

## Sicurezza dinamica (ephemeral QA)
- Intent shell UID 2000 a `DatabaseActivity` non esportata: **`SecurityException: Permission Denial`**, PASS.
- Query shell UID 2000 a `PlacesProvider` non esportato: **`SecurityException: Permission Denial`**, PASS.
- 8 URI esterni malformati via `am start`: 0 righe `AndroidRuntime:E` riferite a PH durante il test, senza prova di assenza di crash non-fatali.
- Invalide credenziali API CI, nessuna richiesta a conti bancari, nessun database utente, nessuna installazione sul Pixel.
- Non testati: attacchi di processo privilegiato, Android 17, SQLCipher, backup SAF con DB utente, tutti i receiver esportati, livelli di rete in un ambiente reale.

## Evidenze riproducibili
- Esecuzione di tre job: https://github.com/gernalix/PersonalHub/actions/runs/40535233287
- Sweep + SQLite: https://github.com/gernalix/PersonalHub/actions/runs/40535233287/artifacts/12748725750
- JUnit XML: https://github.com/gernalix/PersonalHub/actions/runs/40535233287/artifacts/12749101326
- Profiling Android + Perfetto: https://github.com/gernalix/PersonalHub/actions/runs/40535233287/artifacts/12748287744
- Analisi finale dettagliata della traccia: https://github.com/gernalix/PersonalHub/actions/runs/40537363332/artifacts/12748743626

## Conclusione e confini
Due difetti confermati da lettura semantica, un bug geografico dimostrato da test, un caso di avvio lento e frame lenti sull'emulatore; restanti sono rischi che richiedono prova mirata. Non rielaborare tutto PH indiscriminatamente. Nessuna correzione al codice applicativo, merge, APK finale o alterazione dati avvenuta. Il ramo di audit conserva il test fallito come regressione e gli strumenti di prova. L'operazione Fedora `mv-git-finalize` non è eseguibile per esaurimento quota Desktop Commander, quindi non è stata dichiarata eseguita.
