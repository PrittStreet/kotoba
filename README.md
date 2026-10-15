# Kotoba 言葉

Traducteur français ⇄ japonais propulsé par Claude, pensé pour le téléphone.

## Installer l'app Android

1. Sur le téléphone, télécharge `kotoba.apk` depuis la [dernière Release](https://github.com/PrittStreet/kotoba/releases/latest).
2. Ouvre le fichier. Android demande d'autoriser l'installation depuis cette source (Chrome, Fichiers…) : accepte.
3. Ouvre Kotoba, touche ⚙ et colle ta clé API Claude
   (à créer sur [platform.claude.com](https://platform.claude.com/settings/keys), avec un peu de crédit).

Une nouvelle version s'installe par-dessus l'ancienne : historique, favoris et clé sont conservés.

## Le dépôt

- `web/` : l'application (HTML/CSS/JS, sans build). Elle appelle l'API Claude directement avec la clé
  enregistrée dans les réglages ; ça marche aussi dans Chrome, avec la dictée du navigateur.
- `android/` : l'app Android qui affiche `web/` en plein écran et ajoute un micro par langue
  (reconnaissance vocale d'Android), les voix, l'appareil photo et le presse-papiers.
- `artifact/kotoba.html` : la version d'origine, qui tourne comme artifact dans Claude (abonnement).

## Construire l'APK

Le workflow GitHub Actions « APK » construit l'app à chaque push et la publie dans les Releases
quand le push est sur `main`.

En local (JDK 17 ou plus, SDK Android) :

```sh
cd android
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

La clé de signature `android/kotoba.keystore` est versionnée pour que chaque build s'installe
par-dessus le précédent. Le dépôt étant public, n'installe Kotoba que depuis ce dépôt.
