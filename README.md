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
- **Backup e ripristino**: un unico archivio `.zip` con media, tag, note, cronologia e profili
- Tema chiaro/scuro di sistema (colori dinamici da Android 12), italiano e inglese

### Rispetto a SHARE2me, mancano

- la "Floating Ball" che rileva i link copiati
- la navigazione per username (storie, highlights, post di un profilo)
- la selezione multipla trascinando, il tema AMOLED, le altre lingue

## Compilazione

Servono JDK 17+ e l'SDK Android (API 35); il modo più semplice è aprire la cartella con **Android Studio**.
Da riga di comando:

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

In alternativa, caricando il progetto su GitHub, il workflow `.github/workflows/build.yml`
compila l'APK e lo allega come artifact a ogni push.

Se la compilazione segnala errori su `YoutubeDL.UpdateChannel` o `version(...)` in
`ui/Screens.kt`, l'API della libreria è cambiata rispetto alla 0.17.4: sono le uniche due chiamate
non essenziali e si possono adattare o rimuovere senza toccare il resto.

## Struttura

| File | Ruolo |
|---|---|
| `Library.kt` | Modello dati e archivio: elementi, tag, cronologia in un solo `library.json` |
| `Downloader.kt` | yt-dlp + ripiego per link diretti e pagine con `og:video` / `og:image`; importazione locale |
| `DownloadService.kt` | Servizio in primo piano con la coda dei download |
| `Backup.kt` | Esportazione e ripristino dell'archivio zip |
| `MainActivity.kt` | Ricezione dei link condivisi, navigazione, tema |
| `ui/Gallery.kt`, `ui/Detail.kt`, `ui/Screens.kt`, `ui/Common.kt` | Interfaccia in Jetpack Compose |

I file scaricati stanno nella cartella privata dell'app (`Android/data/org.salvami.app/files/media`):
non serve alcun permesso di archiviazione, e vengono rimossi disinstallando l'app (fai prima un backup).

## Limiti noti

- Funziona con i **contenuti pubblici**. Non c'è login: storie, profili privati e contenuti che
  richiedono un account non si scaricano.
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
