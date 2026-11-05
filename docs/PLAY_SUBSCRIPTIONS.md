# Abbonamenti Google Play in Soldi

Da **Soldi → Altro → Abbonamenti Google Play** si può autorizzare l'accesso
alle sole sottoscrizioni, importare un JSON o ZIP Google Takeout, consultare
stato/rinnovo/scadenza, attivare aggiornamenti giornalieri e promemoria tre
giorni prima del rinnovo, e associare gli addebiti bancari compatibili.

## Dati e contabilità

Il catalogo è un aggregato JSON limitato a 16 MiB nel namespace SQLite
`hub_preferences/soldi.play_subscriptions.v1`, attraverso il componente
esistente `DatabasePreferences` e il suo gate transazionale. Non nasce un
secondo database né cambia lo schema Room. Il catalogo, le preferenze e le
associazioni viaggiano con import/export atomico di `personalhub.db`.
I token OAuth e gli URL firmati non entrano nel database, backup o log.

Importare aggiorna le identità stabili già presenti; non cancella gli abbonamenti
assenti da un archivio successivo (che potrebbe essere parziale). Quando uno snapshot
API completo non conferma più un record, ne conserva lo storico ma lo esclude
da previsioni e promemoria finché non viene nuovamente confermato. Un record
senza identità stabile blocca l'importazione invece di deduplicare per nome.
Un errore di lettura/parsing non applica un catalogo parziale. Si conservano i
record originali, incluse le modifiche `userChangeRecord`.

Le previsioni sono separate dai movimenti contabili: importare, aggiornare o
associare un abbonamento non crea una transazione né una ricorrenza contabile.
Le stime normalizzano prezzi e periodi riconosciuti per valuta, senza sommare
EUR e DKK né convertire valute. Stati non confermati, abbonamenti non rinnovabili
e prezzi/periodi mancanti sono esclusi esplicitamente. I campi annidati di
`pricing` non sono specificati completamente dalla documentazione pubblica:
le forme riconosciute sono importi decimali con valuta e periodo ISO 8601,
Money `units/nanos/currencyCode`, oppure `priceMicros/currencyCode`.
Formati di prezzo non riconosciuti restano non disponibili: nessun prezzo o
periodo viene inventato. I test usano dati sintetici, non export dell'account.

La riconciliazione usa la cache bancaria esistente solo se completa e non stale,
con addebiti BOOK/DBIT, stessa valuta/importo, nome del servizio nella descrizione
e data entro tre giorni dal ciclo noto. Corrispondenze ambigue sono escluse.
La conferma rilegge la cache e il catalogo prima di salvare il collegamento;
un addebito non si può riutilizzare. Nessuna API bancaria di scrittura è invocata.

## OAuth e prerequisiti esterni

Si riusa Google Identity Services `AuthorizationClient`, con il solo scope
`https://www.googleapis.com/auth/dataportability.play.subscriptions` e
`setOptOutIncludingGrantedScopes(true)`. Non si mescolano scope email/profile.
Google Play services conserva l'autorizzazione e fornisce token in memoria:
nessun client secret, password Google, refresh token, token BWS o file di
credenziali viene distribuito nell'APK. È l'eccezione specialistica della
sessione nativa della piattaforma, non un nuovo archivio applicativo di segreti.
Eventuali future credenziali di backend devono usare BWS, senza inserirle nell'app.

Per l'uso reale occorrono un progetto Google Cloud autorizzato per Data
Portability, configurazione del consenso e un client OAuth Android associato
al package e al certificato di firma effettivi. Le build QA usano package/firma
propri e richiedono registrazione distinta per provare il consenso reale.
I permessi e la durata (una tantum, 30/180 giorni, oppure limiti Google per app
in testing) dipendono dal consenso del titolare. L'app mostra la necessità di
rinnovare l'accesso invece di aggirarla. L'implementazione e i test sintetici
non attestano configurazione Cloud né accesso effettivo all'account.

Il client richiede snapshot completi `play.subscriptions`, senza filtri sulla
data di inizio, per non perdere modifiche a sottoscrizioni vecchie. Conserva
l'ID del job in preparazione e rispetta almeno 24 ore fra nuovi export. Il
polling differito usa WorkManager con rete disponibile; Android può ritardarlo.
Un'autorizzazione una tantum disattiva gli export periodici dopo il completamento.
Gli URL di download firmati sono usati solo in memoria su HTTPS Google Storage,
senza inoltrare il token OAuth e senza redirect automatici. Gli ZIP vengono
letti in streaming con limiti su dimensione decompressa e numero di file,
senza estrarre percorsi sul filesystem.

I promemoria riusano `FinanceReminderScheduler` e vengono ripristinati al reboot
ed aggiornamento APK con gli stessi receiver e canali esistenti. Le notifiche
richiedono il permesso Android; non sono notifiche Telegram/Fedora.

## Riferimenti ufficiali

- [Schema Google Play](https://developers.google.com/data-portability/schema-reference/play)
- [Durata e frequenza degli export](https://developers.google.com/data-portability/user-guide/time-based)
- [Restrizioni degli scope OAuth](https://developers.google.com/data-portability/user-guide/configure-oauth)
- [Autorizzazione Android](https://developer.android.com/identity/authorization)
