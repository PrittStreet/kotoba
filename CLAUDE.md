# Kotoba — traducteur français ⇄ japonais

Projet de Ronan, francophone qui vit au Japon. Il utilise l'app depuis son téléphone Android
(clavier Gboard, français + japonais installés). Réponds-lui en français, en le tutoyant.

## D'où on part

`artifact/kotoba.html` est la version actuelle, publiée comme artifact Claude
(https://claude.ai/artifact/LpkuKNqT5KSnzNHgG4YWH1). Elle appelle Claude via la capacité
`sample` des artifacts, donc sur l'abonnement de Ronan. Elle marche, mais trois limites
de l'environnement artifact ont motivé la migration :

1. **Pas de micro** : les pages artifact n'ont pas accès au micro, donc pas de Web Speech API.
   La dictée passe par le micro du clavier.
2. **Langue du clavier non pilotable** : l'attribut `lang` du champ est ignoré par Gboard,
   donc la dictée japonaise oblige à basculer le clavier à la main.
3. **Pas d'app autonome** : il faut ouvrir Claude pour l'utiliser, pas de vrai plein écran.

Une app hors de Claude ne peut pas utiliser l'abonnement : la version autonome passe par
une **clé API Anthropic** (facturée à l'usage, à part de l'abonnement).

## Objectif : version autonome (web app installable)

- Page statique (HTML/CSS/JS sans build), hébergée sur **GitHub Pages**.
- **Installable depuis Chrome** (manifest + service worker) : icône, plein écran (`display: standalone`).
- Appels directs à l'API Messages depuis le navigateur :
  `POST https://api.anthropic.com/v1/messages` avec `x-api-key`, `anthropic-version: 2023-06-01`
  et `anthropic-dangerous-direct-browser-access: true`, en streaming (SSE).
  Vérifier ces détails dans la doc officielle avant de coder.
- **Clé API saisie par Ronan dans les réglages**, stockée seulement dans le `localStorage` du
  téléphone. Jamais dans le dépôt. App à usage personnel : ne pas la partager avec la clé dedans.
- Modèles : `claude-haiku-5-5` pour le texte (rapide, ~1 $ pour 10 000 phrases),
  `claude-sonnet-5-5` pour les photos et le mode « Soigné ». Vérifier les identifiants actuels.

### Ce que la version autonome doit ajouter

- **Deux boutons micro** (français / japonais) avec la Web Speech API de Chrome :
  `recognition.lang = 'fr-FR'` ou `'ja-JP'`, puis traduction automatique à la fin de la phrase.
  C'est la demande principale : dicter en japonais sans toucher au clavier.
- Garder le clavier ouvert / un seul appui par phrase autant que possible.

### Ce qu'il faut reprendre de l'artifact (déjà codé dans `artifact/kotoba.html`)

- Détection automatique FR/JA, traduction en streaming, rōmaji, note de nuance (format `@@R` / `@@N`).
- Réglages Rapide / Soigné, Poli / Familier, Rōmaji.
- Écouter (speechSynthesis), Copier, Montrer (plein écran pour l'interlocuteur), Favoris, historique.
- Photos : bouton appareil photo (`capture="environment"`) et bouton galerie, recompression JPEG,
  **traduction façon Google Lens** : Claude renvoie un objet JSON par ligne
  (`{"b":[x0,y0,x1,y1],"o":…,"t":…}`, coordonnées 0–1000), affiché en superposition sur l'image,
  avec bouton Original, plein écran zoomable, et détail d'un bloc au toucher.
- Design : seal 言葉, indigo (藍) + vermillon (朱), Zen Kaku Gothic New, thème clair et sombre.

## Points à valider avec Ronan

- Création de la clé API sur la console Claude et ajout de crédit.
- Activation de GitHub Pages sur ce dépôt.
