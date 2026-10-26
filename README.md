# Sommeil

Estime ta durée de sommeil à partir des moments où l'écran de ton téléphone s'éteint le soir et se rallume le matin. Tout reste sur le téléphone, rien n'est envoyé nulle part.

## Ouvrir le projet

1. Installe Android Studio (version récente).
2. `File > Open` et choisis le dossier `SommeilApp`. Laisse la synchronisation Gradle se faire.
3. Branche ton téléphone (débogage USB activé) ou lance un émulateur, puis clique sur Run.

## Premier lancement

L'appli demande l'accès aux données d'utilisation (« Accès spécial »). Active-le pour Sommeil dans les réglages qui s'ouvrent, puis reviens dans l'appli.

## Comment ça marche

- Android enregistre chaque allumage et extinction d'écran. L'appli relit ces événements, sans service permanent donc sans impact sur la batterie.
- Pour chaque nuit, elle cherche entre 18 h la veille et 14 h le jour du réveil la plus longue pause d'utilisation, encadrée par une vraie utilisation le soir et le matin.
- Les utilisations de moins de 5 minutes sont ignorées (regarder l'heure, notification qui allume l'écran). Réglable dans l'appli.
- Les applications ignorées (YouTube et YouTube Music par défaut, modifiable dans l'appli) ne comptent pas comme éveil : si l'écran reste allumé sur une vidéo, l'endormissement est calé sur la dernière utilisation d'une autre application. Limite : une vraie soirée passée sur YouTube avant de dormir est aussi ignorée, l'heure de coucher peut donc tomber un peu tôt. Le délai d'endormissement permet de compenser.
- Un délai d'endormissement (10 min par défaut) est ajouté après la dernière utilisation. Réglable aussi.
- Une tâche de fond met l'historique à jour deux fois par jour : Android ne garde les événements d'écran qu'environ une semaine, les nuits calculées sont donc enregistrées localement.

## Limites

- C'est une estimation : si tu restes sur ton téléphone au lit ou si tu le laisses allumé en charge, le résultat sera décalé.
- Un réveil nocturne de plus de 5 minutes sur le téléphone coupe la nuit en deux, et la plus longue partie est retenue.
- Android 9 ou plus.
- Le code n'a pas encore été compilé ni testé sur un vrai téléphone. Les tests de `SleepCalculator` sont dans `app/src/test`.
