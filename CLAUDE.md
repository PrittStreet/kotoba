# Kotoba — traducteur français ⇄ japonais

Projet de Ronan, francophone qui vit au Japon. Il utilise l'app depuis son téléphone Android
(clavier Gboard, français + japonais installés). Réponds-lui en français, en le tutoyant.

## Historique

`artifact/kotoba.html` est la première version, publiée comme artifact Claude
(https://claude.ai/artifact/LpkuKNqT5KSnzNHgG4YWH1). Elle appelle Claude via la capacité
`sample` des artifacts, donc sur l'abonnement de Ronan. Trois limites de l'environnement artifact
ont motivé la version autonome : pas de micro (dictée seulement via le clavier), langue du clavier
non pilotable (`lang` ignoré par Gboard), pas d'app autonome.

Une app hors de Claude ne peut pas utiliser l'abonnement (ni la connexion de Claude Code) :
la version autonome passe par une **clé API Anthropic**, facturée à l'usage, à part de l'abonnement.

## Version actuelle : app Android (APK)

- `web/` : l'app, en HTML/CSS/JS sans build (`index.html`, `app.css`, `app.js`).
  Appels directs à `POST https://api.anthropic.com/v1/messages` en streaming SSE, avec `x-api-key`,
  `anthropic-version: 2023-06-01` et `anthropic-dangerous-direct-browser-access: true`.
  Clé saisie dans les réglages, stockée seulement dans le `localStorage` (`kotoba.apikey`),
  jamais dans le dépôt. Le test de clé utilise `GET /v1/models` (gratuit).
  Les clés « personnelles » du Console (`sk-ant-usr-…`) non liées à un workspace exigent l'en-tête
  `anthropic-workspace-id` : champ optionnel « Workspace » dans les réglages (`kotoba.workspace`).
  L'app ne peut pas le trouver seule : l'Admin API (`/v1/organizations/…`) refuse le CORS.
- Modèles : `claude-haiku-5-5` (texte « Rapide »), `claude-sonnet-5-5` (« Soigné » et photos),
  `output_config.effort: "low"`. Sonnet avec `fallbacks: "default"` + `anthropic-beta:
  server-side-fallback-2026-07-01` ; pas de fallback possible sur Haiku. `stop_reason: "refusal"` géré.
  Les réglages affichent une estimation du coût (prix par million de tokens dans `PRICES`).
- `android/` : projet Gradle (AGP 8.13, Java, minSdk 26, targetSdk 36, package
  `io.github.prittstreet.kotoba`). `MainActivity` affiche `web/` (copié dans les assets) via
  `WebViewAssetLoader` sur `https://appassets.androidplatform.net/assets/index.html`.
  - Pont JS `window.KotobaAndroid` (`Bridge.java`) : `canListen`,
    `startListening("fr-FR"|"ja-JP", silenceMs)`, `stopListening`, `cancelListening`,
    `keyboard("fr"|"ja", show)`, `speak(text, "fr"|"ja")`, `stopSpeaking`, `voice("female"|"male")`,
    `voices`, `copy`, `insets`, `darkBars`, `config`, `version`. Retours vers la page : `kotobaNative.speech({type, text, code})`
    (`ready`, `partial`, `final`, `error`, `log`, `timer` avec `ms`), `kotobaNative.keyboard({wanted,
    actual, ime})`, `kotobaInsets(top, bottom)`, `kotobaBack()`.
  - Micro : `SpeechRecognizer` d'Android (`SpeechInput.java`), langue imposée par le bouton :
    c'est ce qui permet de dicter en japonais sans toucher au clavier. Le recognizer coupe seul
    après ~1 s de silence : une dictée enchaîne donc plusieurs sessions, sur le même recognizer et
    sans délai (sinon les mots dits pendant la relance sont perdus), jusqu'au délai de silence
    choisi dans les réglages (`state.silence`, 5 s par défaut). Pas d'extras
    `EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS` : résultats imprévisibles. Chaque étape est
    écrite dans le journal de dictée (Réglages > Dictée, événements `log`) pour le dépannage sur le
    téléphone. Hors Android, la page se rabat sur la Web Speech API (`continuous` + même délai),
    puis sur le micro du clavier.
  - Minuteur : chaque fois que le délai de silence repart (début, mots entendus), un événement
    `timer` fait se vider le contour du bouton micro (SVG `.ring`, `pathLength=100`) sur cette durée.
  - Clavier : petit bouton à côté de chaque micro. `KotobaWebView` donne la langue au clavier via
    `EditorInfo.hintLocales`, relu après `restartInput`. Gboard la suit ; le clavier Samsung non.
    900 ms après, l'app lit la langue réellement affichée (`getCurrentInputMethodSubtype`).
    Mode « rōmaji → かな » (`state.kana`, Réglages > Clavier) pour ces claviers : avec le bouton
    clavier 日本語, le rōmaji tapé devient des kana en direct (`toKana`, table `KANA`), le dernier
    « n » devient ん à l'envoi. Il s'active seul quand le clavier reste en alphabet côté japonais.
    Dans ce mode, `autocomplete` / `autocorrect` / `autocapitalize` = off et pas de correcteur :
    sinon le clavier « corrige » le rōmaji en mots français.
  - Photos : `capture` → appli appareil photo via `FileProvider` (pas de permission CAMERA),
    sinon sélecteur de photos. Les photos HEIF (« haute efficacité » des Samsung) ou de plus de
    8 Mo (50 et 200 Mpx du S23 Ultra) sont converties en JPEG d'environ 2048 px, à l'endroit
    (`Photos.java`, `ImageDecoder`).
  - Photos de travers : téléphone pointé vers le bas (machine à laver, table), l'appareil photo
    enregistre souvent la photo de côté. Claude donne `turn` (ligne du titre : comment la photo est
    tournée) et `r` par bloc (sens du texte, en degrés horaires ; absent = `turn`). La photo est
    affichée tournée pour que son texte se lise à l'endroit, chaque traduction suit son texte, et
    le bouton ↻ (fil et plein écran) tourne d'un quart à la main.
  - Voix : `TextToSpeech` (`Voice.java`), femme ou homme (`state.voice`, Réglages > Voix).
    Android ne donne pas le genre d'une voix : il est deviné par son nom (codes des voix Google,
    `jac`/`jad`/`frb`/`frd` = homme). Sans voix d'homme installée, la voix par défaut est rendue
    plus grave (`setPitch`). Hors Android : Web Speech, même principe.
  - Plein écran bord à bord : la page dessine sous les barres système et s'en écarte avec
    `--inset-top` / `--inset-bottom` ; le clavier réduit la WebView.
  - Pas de `backdrop-filter` sur les traductions des photos : sur un Samsung, elles se
    décalaient de la photo quand on faisait défiler pendant la traduction (calques GPU).
  - Taille du texte : la WebView suit la police système, plafonnée à 120 % (`setTextZoom`) ;
    le CSS tient jusque-là à 320 px de large (le micro perd son icône avant de couper la langue).
- Signature : `android/kotoba.keystore` (mot de passe dans `app/build.gradle`), versionné exprès
  pour que chaque build s'installe par-dessus le précédent sans perdre les données.
  `versionCode` = minutes depuis le 1er janvier 2026 : toujours croissant.
- APK « amis » avec clé intégrée : créer `android/kotoba-secrets.properties` (`apiKey=…`,
  `workspace=…`, ignoré par git), compiler, envoyer l'APK à Ronan, puis supprimer le fichier et
  `android/app/build`. La clé passe par `BuildConfig` et `Bridge.config()` ; la page l'utilise si
  aucune clé n'est saisie et ne l'affiche jamais. Ne jamais la commiter : dépôt et Releases publics,
  et Anthropic révoque toute clé publiée sur GitHub.
- CI : `.github/workflows/apk.yml` construit + lint à chaque push, publie une Release `apk-N`
  (avec `kotoba.apk`) sur `main`. Lien stable : `releases/latest/download/kotoba.apk`.

Comportement voulu par Ronan (définitif) : côté français, Claude est son interprète, pas un
traducteur mot à mot. Quand Ronan décrit ce qu'il veut (« demande au pharmacien s'il a
l'équivalent du Microlax »), Claude écrit directement la phrase à dire en japonais, à la première
personne, avec les mots que les Japonais connaissent. Pour vérifier, Claude renvoie aussi la
traduction mot à mot en français de son japonais (section `@@F`), affichée par les petits boutons
JA / FR au bout de la ligne rōmaji. La note `@@N` reste réservée aux nuances. Côté japonais : traduction du sens,
avec le contexte de la conversation. Même modèle, même vitesse : tout est dans `textPrompt`.

Conversation et favoris (voulu par Ronan) : la conversation affichée sert de contexte aux
traductions ; les favoris sont à part et n'en servent jamais. La poubelle efface ce qui est
affiché, toujours après confirmation : la conversation (ses favoris en sortent avec `out: true`
mais restent dans les favoris) ou, depuis la vue ★, les favoris (ceux encore dans la conversation
y restent, sans étoile).

Ce qui vient de l'artifact et doit rester : détection FR/JA, rōmaji et note de nuance
(format `@@R` / `@@F` / `@@N`), Rapide / Soigné, Poli / Familier, Écouter, Copier, Montrer, Favoris,
historique, photos « façon Google Lens » (une ligne JSON par bloc, `{"b":[x0,y0,x1,y1],"r":…,"o":…,"t":…}`,
coordonnées 0–1000 sur la photo telle quelle), design (sceau 言葉, indigo 藍 + vermillon 朱, Zen Kaku Gothic New, clair/sombre).

## Tester

- Interface : servir `web/` en local et la piloter avec Playwright, API simulée avec `page.route`
  et un faux `window.KotobaAndroid` injecté par `addInitScript`.
- APK : `cd android && ./gradlew assembleRelease lintRelease`. Pas d'émulateur dans les sessions
  cloud (pas de KVM) : micro, caméra et voix se testent sur le téléphone de Ronan.

## Points à valider avec Ronan

- Création de la clé API sur platform.claude.com et ajout de crédit.
- Premier essai sur son téléphone : dictée japonaise, photo, voix japonaise installée.
