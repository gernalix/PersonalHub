# Gmail e riconciliazione Soldi

Da **Soldi → Altro → Gmail e riconciliazione** si autorizza Gmail in sola lettura,
si importa uno storico di 30/90/365 giorni e si può abilitare l’aggiornamento
quotidiano tramite WorkManager. Il consenso reale richiede Gmail API, client
OAuth Android per package/certificato canonici e configurazione del consenso
Google Cloud. Il codice non distribuisce client secret, password o token BWS:
Google Identity conserva la sessione nativa, il token di accesso resta in memoria.

## Archivio unico

Ricevute, ordini, appuntamenti, esclusioni, preferenze, cursore Gmail, cache
bancaria e associazioni persistono nel **personalhub.db principale**, nella
tabella esistente `hub_preferences` (`soldi.gmail.v1`, `soldi.banking.v1`). Non
cambia lo schema Room e non nasce un secondo database. I dati seguono il normale
import/export e la protezione atomica del database principale.

I precedenti file cifrati della cache e riconciliazione bancaria vengono letti,
validati, salvati nel database e riletti prima di rimuovere la vecchia copia.
Le credenziali bancarie mantengono l’eccezione Keystore/noBackupFilesDir.
Corpi email, allegati, token e testo AI non vengono archiviati: solo metadati
utili e provenienza. L’OCR PDF usa file temporanei privati rimossi al termine.

## Acquisizione e revisione

La prima sincronizzazione filtra ricevute/fatture/ordini e inviti ICS. Quelle
successive usano Gmail History per messaggi nuovi/modificati, senza riesaminare
la casella intera. Il cursore avanza solo dopo tutte le pagine e le letture.
Un cursore scaduto (404) richiede una nuova scansione limitata allo storico.
Un errore non applica un catalogo parziale. Sono imposti limiti su pagine,
numero di messaggi, dimensioni e profondità MIME; ridurre lo storico consente
di ripartire da una scansione più piccola. Un account diverso non sovrascrive
quello già presente. Messaggi eliminati, spam o cestinati diventano indisponibili;
i collegamenti già salvati restano consultabili e da rivedere.

I parser riconoscono JSON-LD schema.org Order/Invoice/ParcelDelivery e campi testuali espliciti
(venditore, data ISO, totale con valuta, numero ordine). Non inferiscono valuta
da `$`/`kr` ambigui né usano la ricezione dell’email come data dell’acquisto.
I formati ricorrenti Google Play e i pagamenti PayPal completati hanno parser
dedicati; le autorizzazioni di pagamenti automatici PayPal non diventano ricevute
di addebiti. Sono supportate date con mesi in inglese/italiano/danese e totali
localizzati con suffissi periodici. `kr` resta privo di valuta da confermare.
Formati non riconosciuti restano incompleti: si aprono in Gmail, si verificano
e si completano a mano. I PDF possono essere letti su richiesta con OCR locale
riusando ML Kit (un PDF, fino a 2 MiB e tre pagine); i risultati passano sempre
dalla revisione prima del salvataggio. Sono proposte, non dati certificati.

Le conferme di spedizione espongono il tracking dichiarato senza interrogare
corrieri esterni. I messaggi relativi ad appuntamenti senza ICS restano proposte:
si possono completare data, ora, fuso e luogo, anche con aiuto AI selettivo.

L’AI è opzionale e segue il ponte manuale già usato dagli scontrini: l’utente
sceglie il testo e l’app destinataria, incolla il JSON proposto, controlla ogni
campo e conferma. Non ci sono chiamate AI automatiche, pagamenti, prenotazioni,
invii di email o modifiche remote. Il JSON deve riferirsi allo stesso messaggio.
Le correzioni confermate restano valide anche dopo aggiornamenti dei label Gmail.

Gli inviti ICS rispettano UID, SEQUENCE, RECURRENCE-ID, cancellazione e fuso
orario esplicito; orari locali ambigui non vengono inventati. L’invito apre
l’editor Countdown/Since When esistente con provenienza stabile, per revisione.
Nessuna prenotazione viene creata automaticamente; le ricorrenze ICS non vengono
espanse in serie di nuovi appuntamenti.

## Riconciliazione

Si estende il motore bancario esistente. Per PH↔banca si confrontano conto,
importo con segno, valuta e data entro un giorno. L’intero insieme dei candidati
viene confrontato in entrambe le direzioni: una transazione locale contesa da
due addebiti è ambigua. La scelta confermata è persistente e riserva il movimento
PH; prima di salvare si rileggono stato, cache e valori effettivi nel database.
Un collegamento a un movimento modificato/assente è segnalato per revisione.
È possibile rimuovere l’associazione senza cancellare movimenti.

Per ricevuta↔banca si richiedono addebito BOOK/DBIT, stessa valuta/totale,
venditore compatibile e data entro tre giorni. Le proposte univoche si possono
confermare; per più candidati si sceglie esplicitamente. Ogni ricevuta e ogni
addebito si usano una sola volta nel catalogo. La conferma rilegge anche la cache.
La ricevuta mostra il relativo movimento PH quando esiste un’associazione
bancaria confermata o un suggerimento bancario già accettato. Associare non
inserisce né duplica spese e non cambia saldi. Cache incomplete, obsolete o
parziali non producono nuove associazioni.

I test usano dati sintetici su host e package Android isolato: non dimostrano
il consenso Google Cloud né un accesso effettivo alla posta dell’utente.
