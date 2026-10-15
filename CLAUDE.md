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
    `keyboard("fr"|"ja", show)`, `speak(text, "fr"|"ja")`, `stopSpeaking`, `copy`, `insets`,
    `darkBars`, `version`. Retours vers la page : `kotobaNative.speech({type, text, code})`
    (`ready`, `partial`, `final`, `error`), `kotobaInsets(top, bottom)`, `kotobaBack()`.
  - Micro : `SpeechRecognizer` d'Android (`SpeechInput.java`), langue imposée par le bouton :
    c'est ce qui permet de dicter en japonais sans toucher au clavier. Le recognizer coupe seul
    après 1–2 s de silence : une dictée enchaîne donc plusieurs sessions jusqu'au délai de silence
    choisi dans les réglages (`state.silence`, 5 s par défaut). Hors Android, la page se rabat sur
    la Web Speech API (`continuous` + même délai), puis sur le micro du clavier.
  - Clavier : petit bouton à côté de chaque micro. `KotobaWebView` donne la langue au clavier via
    `EditorInfo.hintLocales` (Gboard la suit si elle est installée), relu après `restartInput`.
  - Photos : `capture` → appli appareil photo via `FileProvider` (pas de permission CAMERA),
    sinon sélecteur de photos. Voix : `TextToSpeech` (`Voice.java`).
  - Plein écran bord à bord : la page dessine sous les barres système et s'en écarte avec
    `--inset-top` / `--inset-bottom` ; le clavier réduit la WebView.
- Signature : `android/kotoba.keystore` (mot de passe dans `app/build.gradle`), versionné exprès
  pour que chaque build s'installe par-dessus le précédent sans perdre les données.
  `versionCode` = minutes depuis le 1er janvier 2026 : toujours croissant.
- CI : `.github/workflows/apk.yml` construit + lint à chaque push, publie une Release `apk-N`
  (avec `kotoba.apk`) sur `main`. Lien stable : `releases/latest/download/kotoba.apk`.

Ce qui vient de l'artifact et doit rester : détection FR/JA, rōmaji et note de nuance
(format `@@R` / `@@N`), Rapide / Soigné, Poli / Familier, Écouter, Copier, Montrer, Favoris,
historique, photos « façon Google Lens » (une ligne JSON par bloc, `{"b":[x0,y0,x1,y1],"o":…,"t":…}`,
coordonnées 0–1000), design (sceau 言葉, indigo 藍 + vermillon 朱, Zen Kaku Gothic New, clair/sombre).

## Tester

- Interface : servir `web/` en local et la piloter avec Playwright, API simulée avec `page.route`
  et un faux `window.KotobaAndroid` injecté par `addInitScript`.
- APK : `cd android && ./gradlew assembleRelease lintRelease`. Pas d'émulateur dans les sessions
  cloud (pas de KVM) : micro, caméra et voix se testent sur le téléphone de Ronan.

## Points à valider avec Ronan

- Création de la clé API sur platform.claude.com et ajout de crédit.
- Premier essai sur son téléphone : dictée japonaise, photo, voix japonaise installée.
