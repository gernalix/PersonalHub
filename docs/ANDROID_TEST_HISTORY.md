# Storico dei test PersonalHub

Il database dedicato locale è
`~/.local/share/personalhub-qa/test-history.sqlite3`, separato dai dati dell'app,
da MegaVault e da Git. Non richiede servizi. Il file nasce con permessi 0600.

Da questo lavoro, conservare gli esiti dei test Android ricorrenti con
`tools/android_connected_test_gate.py --history-context CONTEXT.json
--history-log EVIDENCE.log -- ...`. Restano obbligatori selezione e verifica del
target, isolamento QA e notifiche pre/post per sessioni esclusive Pixel.

Il contesto JSON contiene esclusivamente metadati non segreti: `device_kind`,
`device_model`, `android_api`, `app_id`, `app_version`, `variant`, `apk_sha256`,
`test_apk_sha256`. Calcolare gli hash sui file esatti installati; non associare
risultati vecchi all'APK corrente. Il gate registra anche crash, esiti parziali
e fallimenti e conserva il proprio codice d'uscita. Un errore di registrazione
non trasforma il risultato in PASS.

Per importare evidenze esistenti:

```bash
python3 tools/android_test_history.py instrumentation --source LOG --device-kind pixel --device-model 'Google Pixel 8a' --android-api 37 --app-id com.gernalix.personalhub.qa --app-version 69 --variant qa-unminified --apk APK --test-apk TEST_APK
python3 tools/android_test_history.py am-start --source LOG --module places --device-kind pixel --app-id com.gernalix.personalhub.qa --apk APK
python3 tools/android_test_history.py junit --source TEST-CLASS.xml --device-kind host
python3 tools/android_test_history.py unittest --source PYTHON-TESTS.log --device-kind host
python3 tools/android_test_history.py summary
```

Usare la versione effettiva del test, non copiare il 69 dell'esempio.
Per JUnit host non inventare un'identità APK. Reimportare la stessa evidenza è
idempotente; una nuova esecuzione, anche con esito e testo identici, resta una
misura distinta grazie al timestamp del file. Il timestamp salvato è quello
dell'evidenza, non un orario di avvio del test presunto.
I log Python unittest senza dettaglio dei casi sono registrati come risultato
di suite, con il conteggio dichiarato nel contesto e la durata complessiva;
non vengono inventati nomi o durate dei singoli test.

Le tabelle sono `runs`, `test_results` e `measurements`; la vista
`startup_history` espone le misure per modulo, build e dispositivo. Esempio:

```sql
SELECT module, app_version, variant, device_model, phase, metric,
       count(*) AS samples, round(avg(value), 1) AS mean_ms,
       min(value) AS best_ms, max(value) AS worst_ms
FROM startup_history
GROUP BY module, app_version, variant, apk_sha256, device_model, phase, metric;
```

`am start -W` produce `activity_total_time` e `activity_wait_time` in millisecondi,
con fase COLD/WARM/HOT riportata da Android. Lo stato `MEASURED` certifica la
lettura dei tempi, non il corretto funzionamento o la piena disponibilità del
contenuto del modulo. Non mescolare build QA non minificate e APK finali,
dispositivi, fasi o dataset diversi nelle comparazioni. Per analisi approfondite
resta disponibile il benchmark Macrobenchmark/Perfetto già presente nel repo.

Lo storico salva hash e percorso dell'evidenza, nomi/esiti dei test e durate
misurate. Non contiene log completi, notifiche, messaggi privati, credenziali o
valori estratti dalle asserzioni fallite. Non modifica gli esiti precedenti:
una correzione genera una nuova esecuzione.
