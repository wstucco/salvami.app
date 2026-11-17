# Salvami

App Android open source per **salvare foto e video dai link condivisi** in una galleria offline,
con tag, preferiti, note, ricerca e backup. Nessuna pubblicità, nessun tracciamento:
tutto resta sul dispositivo.

Il download è affidato a [yt-dlp](https://github.com/yt-dlp/yt-dlp), incluso nell'app tramite
[youtubedl-android](https://github.com/yausername/youtubedl-android), quindi funziona con i siti che yt-dlp supporta.

> **Stato:** prima versione (0.1). Provata su Android 16 con Instagram, YouTube e HLS;
> le storie Instagram attendono una correzione di yt-dlp.

## Funzioni

- **Condividi → Salvami**: dal menu "Condividi" di qualsiasi app, oppure "Incolla link" nell'app
- **Download in background** con notifica di avanzamento, coda, annullamento e "riprova"
- **Cronologia** dei download con data e stato (in coda, in corso, completato, non riuscito)
- **Galleria offline** a griglia, da 2 a 6 colonne
- **Visualizzatore** a schermo intero: scorrimento tra elementi, zoom con due dita o doppio tocco, riproduzione video
- **Dettagli**: nome file, risoluzione, dimensione, data, piattaforma, username, link originale
- **Tag colorati** (più tag per elemento), **preferiti** e **note**
- **Ricerca** su titolo, username, piattaforma, note e tag; **filtri** per tipo, preferiti, note, tag; **ordinamento** per data, username, dimensione
- **Selezione multipla** (pressione lunga) per condividere, eliminare, mettere tra i preferiti o taggare in blocco
- **Importa dal dispositivo** foto e video nella stessa galleria
- **Apri con** un'altra app galleria / player
- **Profili**: condividendo il link di un profilo (Instagram, TikTok, X, YouTube, Facebook) lo si salva
  in una sezione apposita, con nome e foto, invece di scaricarne i contenuti
- **Account**: accesso dalla pagina di login del sito, per i contenuti privati; la sessione resta sul telefono
- **Backup**: la libreria sta in una cartella visibile (`Android/media/org.salvami.app`) che un'app di backup
  o sincronizzazione può copiare, e si ripristina da quella copia; in alternativa, un unico archivio `.zip`
- Tema chiaro/scuro di sistema (colori dinamici da Android 12), italiano e inglese

### Rispetto a SHARE2me, mancano

- la "Floating Ball" che rileva i link copiati
- il download in blocco di storie, highlights e post di un profilo
- la selezione multipla trascinando, il tema AMOLED, le altre lingue

## Installazione

Scarica dalla pagina [Releases](https://github.com/wstucco/salvami.app/releases) l'APK per il tuo telefono:
`app-arm64-v8a-release.apk` va bene per quasi tutti i dispositivi recenti (`armeabi-v7a` per quelli
più vecchi a 32 bit, `x86_64`/`x86` per emulatori e Chromebook). Gli aggiornamenti si installano sopra
la versione precedente senza perdere la galleria.

## Compilazione

Servono JDK 21 (con [SDKMAN!](https://sdkman.io) basta `sdk env`, che legge `.sdkmanrc`) e l'SDK
Android (API 35); in alternativa si apre la cartella con **Android Studio**. Da riga di comando:

```bash
./gradlew assembleDebug
# un APK per architettura in app/build/outputs/apk/debug/
```

Le build `release` sono ridotte con R8 e, senza le variabili di firma, firmate con la chiave di debug.
Durante la build il task `stripPython` toglie dal Python incluso nella libreria i moduli che yt-dlp non usa.

### CI e release

Il workflow `.github/workflows/build.yml` compila le APK `release` a ogni push su `main` e le allega
come artifact. Con un tag `v*` pubblica anche una GitHub Release:

```bash
git tag -a v0.2.0 -m "Salvami 0.2.0" && git push origin v0.2.0
```

La versione deriva dai tag (`v0.1.0` → `0.1.0`, i commit successivi → `0.1.0-3-gabc1234`) e il
`versionCode` dal numero di esecuzione del workflow. La firma usa i segreti del repository
`SALVAMI_KEYSTORE_BASE64`, `SALVAMI_KEYSTORE_PASSWORD`, `SALVAMI_KEY_ALIAS` e `SALVAMI_KEY_PASSWORD`;
la chiave va conservata con cura, perché senza di essa non si possono più pubblicare aggiornamenti installabili.

## Struttura

| File | Ruolo |
|---|---|
| `App.kt` | Avvio: estrazione di Python e yt-dlp, aggiornamento giornaliero di yt-dlp |
| `Library.kt` | Modello dati e archivio: elementi, tag, cronologia e profili in un solo `library.json` |
| `Downloader.kt` | yt-dlp, avanzamento, ripiego per link diretti e pagine con `og:video` / `og:image`; importazione locale |
| `Muxer.kt` | Unione di audio e video e conversione MPEG-TS → MP4 con `MediaMuxer`, al posto di FFmpeg |
| `DownloadService.kt` | Servizio in primo piano con la coda dei download |
| `Profiles.kt` | Riconoscimento dei link ai profili, nome e foto dall'anteprima della pagina |
| `Accounts.kt`, `LoginActivity.kt` | Accesso ai siti in una WebView e cookie di sessione per yt-dlp |
| `Backup.kt` | Esportazione e ripristino dell'archivio zip |
| `MainActivity.kt` | Ricezione dei link condivisi, navigazione, tema |
| `ui/` | Interfaccia in Jetpack Compose: galleria, dettaglio, profili, download, impostazioni |

Tutta la libreria sta in `Android/media/org.salvami.app/`, che non richiede permessi e, a differenza
di `Android/data`, è leggibile da file manager e app di backup (Syncthing, FolderSync, …):

```
library.json   tag, preferiti, note, cronologia, profili
media/         foto e video
avatars/       foto dei profili
.nomedia       tiene i download fuori dalla galleria di sistema (Impostazioni → Mostra nella galleria)
```

Android cancella la cartella quando l'app viene disinstallata: fanne copiare il contenuto a un'app di backup
e, dopo una reinstallazione, usa *Impostazioni → Ripristina da una copia della cartella*. Le sessioni degli
account restano invece nella memoria privata dell'app. Le versioni 0.1 tenevano i dati in `Android/data`:
vengono spostati automaticamente al primo avvio.

## Limiti noti

- Senza account si scaricano solo i **contenuti pubblici**. Dopo l'accesso (*Impostazioni → Account*)
  funzionano anche i profili privati che segui e i contenuti con limiti di età; le **storie Instagram**
  non funzionano ancora per un problema di yt-dlp, e arriveranno con un suo aggiornamento.
- I **profili** vengono salvati come segnalibri: i loro post non si scaricano in blocco.
- I **post con sole foto** (anche i caroselli Instagram) vengono salvati alla risoluzione originale.
  Per i siti che yt-dlp non conosce, l'app prova a prendere l'immagine di anteprima della pagina.
- I siti cambiano spesso: l'app aggiorna yt-dlp da sola una volta al giorno; si può forzare da *Impostazioni → Aggiorna yt-dlp*.
- FFmpeg non è incluso: audio e video separati vengono uniti con le API di Android (`MediaMuxer`), senza ricodifica.
  L'APK (circa 15 MB per architettura) contiene yt-dlp e una versione ridotta di Python; il primo avvio richiede qualche secondo per estrarli.

## Uso responsabile

Salvami è pensata per l'uso personale. Scarica solo contenuti che hai il diritto di salvare e
rispetta i termini dei servizi da cui provengono. Non è affiliata ad alcuna piattaforma.

## Licenza

GPL-3.0-or-later (richiesta dalla libreria youtubedl-android). Vedi `LICENSE`.
