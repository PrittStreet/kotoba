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
    `voices`, `copy`, `insets`, `darkBars`, `config`, `cameraLive(key, workspace)`, `version`. Retours vers la page : `kotobaNative.speech({type, text, code})`
    (`ready`, `partial`, `final`, `error`, `log`, `timer` avec `ms`), `kotobaNative.keyboard({wanted,
    actual, ime})`, `kotobaInsets(top, bottom)`, `kotobaBack()`, `kotobaPhoto(url)`.
  - Micro : `SpeechRecognizer` d'Android (`SpeechInput.java`), langue imposée par le bouton :
    c'est ce qui permet de dicter en japonais sans toucher au clavier. Le recognizer coupe seul
    après ~1 s de silence : une dictée enchaîne donc plusieurs sessions, sur le même recognizer et
    sans délai (sinon les mots dits pendant la relance sont perdus), jusqu'au délai de silence
    choisi dans les réglages (`state.silence`, 5 s par défaut). Pas d'extras
    `EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS` : résultats imprévisibles. Chaque étape est
    écrite dans le journal de dictée (Réglages > Dictée, événements `log`) pour le dépannage sur le
    téléphone. Hors Android, la page se rabat sur la Web Speech API (`continuous` + même délai),
    puis sur le micro du clavier.
  - Phrase dictée pendant une traduction : sa carte est créée tout de suite et attend son tour
    (`waiting` + `runNext`), jamais dans le champ. Pendant une dictée, la touche envoyer finit la
    phrase (elle ne devient « stop » qu'en dehors d'une dictée, `syncSend`). Le texte tapé avant
    une dictée revient ensuite, de son côté (`listening.draft`) ; les mots dictés restés dans le
    champ ne sont jamais convertis en かな (`kanaKeep` : plages suivies à chaque modification).
    Une phrase en cours de fin (« Un instant… ») arrive toujours, sauf effacement ou app en
    arrière-plan. Une carte en attente l'indique ; « Réessayer » pendant une traduction attend
    aussi son tour ; la touche stop arrête la traduction, la file et les photos en préparation.
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
  - Photos : `capture` → appli appareil photo via `FileProvider`, sinon sélecteur de photos.
    Piège : la permission CAMERA (déclarée pour la Caméra live) fait refuser par Android l'appli
    appareil photo (`SecurityException`, plantage dans WebView, que Samsung accuse ensuite) tant
    qu'elle n'est pas accordée : `chooseFile` la demande d'abord ; refusée, la galerie s'ouvre. Les photos HEIF (« haute efficacité » des Samsung) ou de plus de
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
  - Mode Live (bouton casque en haut, écran à part, voulu par Ronan sans interférence avec le
    reste) : écoute en continu le japonais autour (annonces, quelqu'un qui lui parle) et le traduit
    phrase par phrase en français, dit dans les écouteurs et affiché. Pas de conversation, rien dans
    le fil ni dans le contexte. Tout tourne côté Android (`LiveService` : service au premier plan
    « microphone », notification avec Arrêter, verrou de veille ≤ 4 h) pour marcher téléphone en
    poche, écran éteint : `SpeechRecognizer` ja-JP en sessions enchaînées, Claude Haiku appelé en
    Java (une phrase à la fois, 3 phrases de contexte, « - » = bruit ignoré), `TextToSpeech` fr en
    file. Micro du téléphone, pas celui des écouteurs (mode appel, filtre les voix autour). Voix
    seulement si des écouteurs sont branchés, sinon le micro l'entendrait. Pont : `liveStart(key,
    workspace, male)`, `liveStop`, `liveState`, `liveUsage` ; retours `kotobaLive({type: state |
    partial | phrase})`. Fermer l'écran Live arrête l'écoute ; rouvrir l'app pendant une session
    affiche l'écran Live. Xiaomi (HyperOS) : régler la batterie de Kotoba sur « Aucune restriction ».
    Sensibilité : sur Android 13+, Kotoba enregistre lui-même le micro du téléphone (`AudioRecord`,
    jamais celui des écouteurs), relève les voix faibles ou lointaines (gain auto ≤ ×8, limiteur) et
    passe ce son au recognizer (`EXTRA_AUDIO_SOURCE`, un tuyau par phrase). Si le recognizer ne le
    lit pas (1,5 s) ou échoue tout de suite 3 fois, retour au micro standard. Barre de niveau et
    journal (Copier) dans l'écran Live pour le dépannage. Avec ce son, le recognizer ne finit jamais
    une phrase seul : Kotoba coupe (1,2 s sans mot nouveau, ou toutes les 12 s), `stopListening`, et
    garde le son entre deux sessions (aucun mot perdu). Choix de Ronan après essai : phrase par
    phrase en français naturel (le mot à mot en vrac par morceaux de 4 s a été écarté).
  - Caméra live (bouton viseur en haut, `CameraLiveActivity`, écran natif à part) : le japonais filmé
    est traduit sur place, étiquettes qui suivent le texte quand le téléphone bouge (« façon Google
    Lens », mais traduit par Claude : voulu par Ronan). Lecture du texte sur le téléphone, hors ligne,
    plusieurs fois par seconde (ML Kit japonais intégré à l'APK). ML Kit reçoit une image déjà
    recadrée sur ce que montre l'aperçu et redressée (`toBitmap` RGBA, `createBitmap` + rotation,
    `InputImage.fromBitmap(…, 0)`), puis simple mise à l'échelle vers l'écran. Ne pas lui laisser la
    rotation (`fromMediaImage(image, rotation)` ou `MlKitAnalyzer`) : sur le Xiaomi, ses positions
    restaient celles de l'image couchée, étiquettes tournées d'un quart de tour. Chaque ligne est suivie d'une image à l'autre
    (`LabelsView` : même texte, ou lecture voisine et ressemblante) et son étiquette glisse vers la
    nouvelle position. Une ligne lue sur 2 images est traduite une seule fois par Claude Haiku
    (`Claude.java`, lignes numérotées, une requête à la fois, réponse en objet JSON `{"1": …}`,
    « - » = bruit ; une ligne oubliée 2 fois reste sans traduction), puis gardée en mémoire (500
    textes, aussi pour une lecture qui diffère d'une lettre). Tout en 4:3 (aperçu `FIT_CENTER` avec
    bandes noires, lecture, photo) : même champ partout. « Photo » prend une vraie photo
    (`IMAGE_CAPTURE`, ~12 Mpx, comme l'appli appareil photo, `cache/photos`), ferme la caméra et la
    passe à la traduction photo habituelle. Pas de capture de l'aperçu (`getBitmap`) : image au
    format de l'écran et moins nette, sur laquelle Claude plaçait mal ses étiquettes
    (`kotobaPhoto('/photos/…')`, servi par `WebViewAssetLoader` ; CSP `connect-src 'self'`).
    « Lampe » si le téléphone en a une. Permission CAMERA demandée par l'écran lui-même. Ses tokens
    comptent dans l'estimation du coût au retour dans l'app (`liveUsage`).
    APK limité aux processeurs ARM (`abiFilters`) : la lecture du texte pèse 11 Mo par type.
  - WebView absent, désactivé ou qui ne démarre pas (par exemple après « Désinstaller » les mises à
    jour d'Android System WebView sur un Samsung) : `MainActivity` affiche un écran d'aide natif
    (Play Store, Réessayer) au lieu de planter au lancement. Pas de contrôle de version : tous les
    fabricants ne la numérotent pas comme Chrome.
  - Plein écran bord à bord : la page dessine sous les barres système et s'en écarte avec
    `--inset-top` / `--inset-bottom` ; le clavier réduit la WebView.
  - Étiquettes des photos (`fitOv`, `untangle`) : le français dans la zone du texte d'origine, aussi
    grand que possible, sans descendre sous 40 % de la taille des caractères d'origine (estimée par
    la zone partagée entre ses caractères, `ov._chars` : un paragraphe a de petits caractères ;
    plancher plafonné à 12 px, minimum absolu 6 px, `OV_MIN`), un peu plus haut que sa ligne au besoin
    (`OV_SLACK` 1,15 : une liste garde ses lignes en place). Sinon l'étiquette s'agrandit autour de sa
    zone (largeur jusqu'à ×2,5, jamais un mot coupé, puis deux lignes), centrée et dans la photo ;
    au-delà, « … » (texte entier au toucher et dans « Texte détecté »). Une étiquette agrandie qui
    couvre vraiment une voisine (> 15 % de sa surface) se décale du plus petit pas.
    Consigne : un panneau, titre, case ou élément de liste = un bloc ; un plat avec ses points et son
    prix = un bloc sur toute la ligne, prix à la fin ; une phrase ou un avis sur plusieurs lignes ou
    colonnes = UN bloc ; jamais plusieurs plats ou panneaux dans un bloc. `r` = rotation des
    caractères : le japonais vertical (縦書き, caractères droits) a r = 0, donc un français droit, lisible
    sans pencher la tête. Panneaux bilingues : la ligne anglaise ou rōmaji qui répète le japonais est
    ignorée. Panneau routier : son sens (symbole + mots), pas seulement ses mots. Médicament, produit,
    aliment : ce que c'est entre parenthèses (« Halcion 0,25 mg (somnifère) »).
    Banc d'essai (octobre 2026) : 28 images réelles (menus, tableaux de départs, tarifs,
    panneaux, affiches manuscrites, texte vertical, médicaments, tri des déchets, emballages) traduites par le vrai Claude, réponses rejouées
    aux largeurs Pixel 9a, S23 Ultra, Xiaomi 17T Pro. Limite connue : un texte très petit ou flou
    (menu manuscrit lointain) n'est pas lu, il faut s'approcher.
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
y restent, sans étoile). Mettre en favori : sans confirmation. Retirer un favori (« Gardé » dans la
conversation, « Supprimer le favori » dans la vue ★) : confirmation dans la carte. Les cartes
EXEMPLE ne s'affichent qu'avant la première traduction (`state.started`) ; la poubelle les retire.
Tout re-rendu du fil (autre vue, nouvelle carte) ferme la confirmation de la poubelle et la fiche
d'un bloc photo. Retour Android, dans l'ordre : fiche, plein écran, Montrer, Réglages, dictée,
confirmation, question d'une carte, vue ★, puis l'app passe en arrière-plan.

Persistance : chaque carte est enregistrée dès sa création ; une traduction coupée par un
redémarrage devient « Traduction interrompue. » avec Réessayer (photos comprises, depuis la photo
1000 px enregistrée). Mémoire pleine : les photos enregistrées sont retirées une à une, des plus
anciennes aux favoris, puis les miniatures.

Ce qui vient de l'artifact et doit rester : détection FR/JA, rōmaji et note de nuance
(format `@@R` / `@@F` / `@@N`), Rapide / Soigné, Poli / Familier, Écouter, Copier, Montrer, Favoris,
historique, photos « façon Google Lens » (une ligne JSON par bloc, `{"b":[x0,y0,x1,y1],"r":…,"o":…,"t":…,"p":…}`,
`b` demandé en pixels de l'image envoyée, sa taille dans la consigne (`it.px`), converti en 0–1000
sur la photo telle quelle à la lecture et stocké ainsi ; demandé en 0–1000, Claude répondait parfois
en pixels : étiquettes trop bas et trop à droite ; `p` = rōmaji du japonais, affiché entre parenthèses
après l'original dans « Texte détecté » et la fiche d'un bloc, pour le prononcer), design (sceau 言葉, indigo 藍 + vermillon 朱, Zen Kaku Gothic New, clair/sombre).

## Tester

- Interface : servir `web/` en local et la piloter avec Playwright, API simulée avec `page.route`
  et un faux `window.KotobaAndroid` injecté par `addInitScript`. Les bugs cachés viennent surtout
  des enchaînements « action puis changement de vue / interruption » (★, Réglages, plein écran,
  retour Android, redémarrage, dictée pendant une traduction) : les tester systématiquement.
- APK : `cd android && ./gradlew assembleRelease lintRelease`. Pas d'émulateur dans les sessions
  cloud (pas de KVM) : micro, caméra et voix se testent sur le téléphone de Ronan.

## Points à valider avec Ronan

- Création de la clé API sur platform.claude.com et ajout de crédit.
- Premier essai sur son téléphone : dictée japonaise, photo, voix japonaise installée.
