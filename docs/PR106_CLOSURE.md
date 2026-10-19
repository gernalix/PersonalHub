# Chiusura mirata PR106 — 19 ottobre 2026

Obiettivo di questa fase: CI nativa PASS, integrazione della PR106 tramite
owner single-writer e purificazione del solo repository PersonalHub, poi stop.
Nessuna nuova feature, installazione Pixel o modifica NV; v73 resta immutata.

## Ripresa e correzione

- Checkpoint ricevuto: `f007b261b6c30d191abf09354793923ed669d9c5`, branch
  `task/ph-widget-recovery-20261019`, unico worktree di sviluppo autorizzato.
- [Run 39297742954](https://github.com/gernalix/PersonalHub/actions/runs/39297742954)
  realmente FAIL: 19 casi PASS, poi
  `GlobalDatabaseInstrumentedTest#repeatedImportsDeleteOrphansButProtectAPendingRollbackCopy`
  rifiuta l'emulatore AOSP (`Android SDK built for x86_64`, hardware `ranchu`).
  Gli altri quattro controlli PR sono PASS. Nessun run duplicato o retry avviato.
- Correzione limitata alla guardia privata del test: riuso del criterio già
  presente nei test Places (modello SDK senza distinzione maiuscole, hardware
  ranchu/goldfish), con ulteriore obbligo di package QA isolato. Le guardie
  physical-only e tutte le asserzioni funzionali rimangono attive.
- Compilazione mirata `:app:compileQaAndroidTestKotlin` PASS con variante QA.
  Nessuna API pubblica cambiata e nessun APK distribuibile costruito.

## Da completare

Attendere il nuovo run nativo sul commit corretto; solo dopo PASS verificare
merge GitHub tramite owner, preservare il checkout e purificare con gli strumenti
esistenti. Il piano di purificazione conferma tre checkout storici frozen da
conservare; checkout e branch correnti restano protetti finché non integrati.
Non dichiarare PASS sulla sola compilazione né sul precedente risultato v72.

Se due nuovi tentativi sulla stessa causa esterna non portano progresso,
terminare BLOCKED con run, causa e Next action. Dopo questa fase non proseguire
l'inventario: prossimo residuo indipendente prioritario è il recupero ADB/VPN
autonomo dopo cold boot, ancora non dimostrato; richiede una fase separata e
le relative garanzie sul dispositivo.
