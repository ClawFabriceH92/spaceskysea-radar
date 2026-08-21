# SpaceSkySea Radar 🛩️🚢⭐

Application Android (Kotlin + Jetpack Compose) qui affiche en temps réel les
**avions** (OpenSky Network) et les **navires** (AISstream.io) autour de vous,
avec un mode **Jumelles** en réalité augmentée (caméra + gyroscope + étoiles).

## Fonctionnalités

- **Carte** — fond CartoDB Voyager (net, 512 px), avions orientés selon leur
  cap et colorés selon la tendance (bleu = monte, rouge = descend), navires
  avec silhouette dédiée, bandeau vitesse GPS, recentrage, fiche détaillée au
  tap (altitude, vitesse, squawk, itinéraire départ → arrivée).
- **Jumelles** — 4 vues :
  - *Horizontal* : boussole + liste des avions dans le cône de visée (±45°),
    mise à jour en direct quand on tourne le téléphone ;
  - *Vertical* : profil du ciel (distance × altitude) devant soi ;
  - *Contrôleur* : tout le trafic du rayon, trié par distance, code couleur
    par altitude ;
  - *Ciel 📷* : réalité augmentée — caméra en fond, avions et étoiles réelles
    (position astronomique calculée) alignés sur la visée, pincer pour zoomer,
    toucher un avion pour sa fiche.
- **Vol** — suivi d'un vol par « compagnie + numéro » (ex : Air France
  AF1234), recherche autour de votre position.
- **Paramètres** — rayons de recherche, fréquence, unités, couches,
  import des credentials OpenSky (fichier ou collage JSON), test de connexion,
  clé AISstream, suivi en arrière-plan optionnel (Foreground Service).
- **Thème** — Material 3, clair et sombre.
- **Auto-update** — vérifie la release GitHub « latest » au lancement et
  télécharge l'APK si une version plus récente existe.

## Clés API (optionnelles mais recommandées)

| Service | Sans clé | Avec clé | Où l'obtenir |
|---|---|---|---|
| OpenSky (avions) | 400 req/jour, pas d'itinéraires | 4000 req/jour + itinéraires | [opensky-network.org](https://opensky-network.org) → compte → API client (`credentials.json`) |
| AISstream (navires) | aucun navire | flux temps réel | [aisstream.io](https://aisstream.io) (gratuit) |

Les clés se saisissent dans l'onglet **Paramètres** et restent sur l'appareil.

## Build

```bash
./gradlew assembleDebug          # APK debug
./gradlew testDebugUnitTest      # tests unitaires (logique pure)
./gradlew assembleRelease        # APK release (signé si keystore configuré)
```

- Android Studio Ladybug+ / AGP 8.7 / Kotlin 2.0 / minSdk 26 / targetSdk 35.
- CI GitHub Actions : build + tests sur chaque push ; un tag `vX.Y.Z` publie
  une release avec les APK et le `version.json` de l'auto-update.

## Données & attributions

- Avions : [OpenSky Network](https://opensky-network.org) (usage non commercial)
- Navires : [AISstream.io](https://aisstream.io)
- Carte : © OpenStreetMap contributors © [CARTO](https://carto.com)

Projet personnel — usage privé/éducatif.
