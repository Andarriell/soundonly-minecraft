# 🎛 SoundOnly — Audio Panel for Minecraft

> Diffuse de la musique en temps réel dans Minecraft via **Simple Voice Chat** — multi-zones, contrôlé depuis un panel web, avec écoute en direct dans le navigateur.

---

## 📋 Présentation

**SoundOnly** est un plugin Paper + un panel web Node.js qui permettent de broadcaster de la musique (MP3, flux Icecast/OBS) directement dans les oreilles des joueurs connectés à Simple Voice Chat.

Chaque zone WorldGuard est indépendante — les joueurs entendent automatiquement la musique de la zone dans laquelle ils se trouvent.

```
[MP3 / Flux Icecast]
        │
        ▼
[FFmpeg] → PCM 16-bit mono 48kHz
        │
        ▼
[Panel Node.js] ──→ WebSocket Minecraft → [Plugin SoundOnly] → Simple Voice Chat → 🎧 Joueurs
        │
        └──→ WebSocket Navigateur → WebAudio API → 🔊 Écoute dans le navigateur
```

---

## ✨ Fonctionnalités v1.0.4

- 🗺 **Multi-zones WorldGuard** — chaque zone a son propre flux audio indépendant
- 🔄 **Routing automatique** — les joueurs entendent la zone dans laquelle ils se trouvent
- 🎵 **Playlists** — lecture enchaînée avec crossfade aléatoire (2-5 secondes)
- ⏸ **Pause / Resume** — reprise à la position exacte dans le fichier
- 🌐 **Page d'écoute publique** — interface immersive avec visualiseur audio
- 💬 **Message de bienvenue** — lien cliquable envoyé à chaque joueur qui rejoint
- 🎮 **Commandes in-game** — `/so zone`, `/so label`, `/so link`, `/so mute`...
- 🔒 **Système de permissions** — joueurs / staff / admin / wildcard
- 📻 **Flux live** — Icecast / OBS en temps réel
- ⏱ **Cadence 20ms** — envoi des frames synchronisé avec la cadence Voice Chat

---

## 🧱 Prérequis

### Serveur Minecraft
| Composant | Version |
|-----------|---------|
| Paper | 1.21.x |
| Simple Voice Chat | 2.6.17+ |
| WorldGuard + WorldEdit | 7.x |

### Machine hébergeant le panel
| Composant | Version |
|-----------|---------|
| Node.js | 18+ |
| FFmpeg + ffprobe | 5+ (`apt install ffmpeg`) |
| npm | 8+ |

---

## 🚀 Installation

### 1. Plugin Minecraft

```bash
cp voicechat-bukkit-2.6.17.jar final_plugin/lib/voicechat-api-2.6.17.jar
cd final_plugin
mvn package -q
cp target/SoundOnly-1.0.0.jar /chemin/vers/plugins/
```

### 2. Panel web

```bash
cd panel
npm install
cp .env.example .env
nano .env
npm start
```

---

## ⚙️ Configuration

### Plugin — `plugins/SoundOnly/config.yml`

```yaml
websocket:
  address: "0.0.0.0"
  port: 8765

panel:
  listener-url: "http://TON-IP:3000/listener.html"
  admin-url: "http://TON-IP:3000"

worldguard:
  enabled: false
  regions:
    - main_stage
    - esprit
    - vegetal
    - futuriste
```

### Panel — `.env`

```env
PANEL_PORT=3000
PANEL_PASSWORD=change-moi
MC_WS_URL=ws://127.0.0.1:8765
PANEL_URL=http://TON-IP:3000
```

---

## 🗺 Zones audio

| ID WorldGuard | Affiché dans le panel |
|---|---|
| `main_stage` | Main Stage |
| `esprit` | Esprit |
| `vegetal` | Vegetal |
| `futuriste` | Futuriste |

---

## 🎮 Commandes in-game

### Commandes joueurs (`default: true`)

| Commande | Description |
|----------|-------------|
| `/so` ou `/so help` | Aide filtrée selon les permissions |
| `/so zone` | Zones actives avec statut live/mute |
| `/so label` | Noms des scènes + morceau en cours |
| `/so link` | Lien cliquable vers la page d'écoute |
| `/so select <zone>` | Sélectionner une zone pour actions suivantes |

### Commandes staff (`default: op`)

| Commande | Description |
|----------|-------------|
| `/so mute [zone]` | Muter une zone (coupe le son aux joueurs) |
| `/so unmute [zone]` | Démuter une zone |
| `/so panel` | Lien vers le panel admin |

### Commandes admin (`default: op`)

| Commande | Description |
|----------|-------------|
| `/so status` | État complet du plugin |
| `/so stop [zone]` | Arrêter une ou toutes les zones |
| `/so reload` | Recharger la config |

---

## 🔒 Permissions

| Permission | Description | Défaut |
|------------|-------------|--------|
| `soundonly.control.*` | Toutes les permissions | op |
| `soundonly.staff` | Staff (mute/unmute/panel/control) | op |
| `soundonly.player` | Joueurs (zone/label/link/select) | true |
| `soundonly.zone` | Voir les zones actives | true |
| `soundonly.label` | Voir les labels et morceaux | true |
| `soundonly.link` | Recevoir le lien d'écoute | true |
| `soundonly.select` | Sélectionner une zone | true |
| `soundonly.mute` | Muter une zone | op |
| `soundonly.unmute` | Démuter une zone | op |
| `soundonly.panel` | Lien panel admin | op |
| `soundonly.control` | Commandes admin | op |

---

## 🌐 Page d'écoute publique

Accessible sur `http://TON-IP:3000/listener.html` — envoyée automatiquement dans le chat à chaque connexion de joueur.

- 4 cartes de zone avec couleur dédiée et indicateur live
- Visualiseur audio canvas en temps réel
- Volume local indépendant par utilisateur
- Play/Pause local
- Polling toutes les 3s pour les zones actives

---

## 🔌 Protocole WebSocket

| Message | Direction | Description |
|---------|-----------|-------------|
| `ping` / `pong` | ↔ | Keepalive |
| `voice_config` | Panel → Plugin | Active/désactive une zone + morceau en cours |
| `voice_audio` | Panel → Plugin | Frame PCM base64 |
| `welcome_config` | Panel → Plugin | URL de bienvenue |
| `zone_create` | Panel → Plugin | Crée une zone |
| `zone_remove` | Panel → Plugin | Supprime une zone |

---

## 📁 Structure

```
SoundOnly/
├── final_plugin/
│   ├── src/main/java/com/soundonly/
│   │   ├── SoundOnlyPlugin.java
│   │   ├── websocket/AudioWebSocketServer.java
│   │   ├── voice/VoicechatIntegration.java
│   │   ├── zone/
│   │   │   ├── AudioZone.java
│   │   │   └── ZoneManager.java
│   │   └── worldguard/WorldGuardFilter.java
│   ├── src/main/resources/
│   │   ├── plugin.yml
│   │   └── config.yml
│   ├── lib/voicechat-api-2.6.17.jar
│   └── pom.xml
└── panel/
    ├── server.js
    ├── .env.example
    ├── package.json
    └── public/
        ├── index.html
        └── listener.html
```

---

## 🔄 Changelog

### v1.0.4
- Commandes `/so` avec alias `/soundonly`
- `/so zone` — zones actives + statut
- `/so label` — noms des scènes + morceau en cours (sans extension)
- `/so link` — lien cliquable vers listener.html
- `/so select` — sélection de zone pour mute/unmute
- `/so mute` / `/so unmute` — mute instantané côté plugin
- `/so panel` — lien panel admin
- Système de permissions complet (player/staff/admin/wildcard)
- URLs configurables dans config.yml comme fallback

### v1.0.3
- Page d'écoute publique immersive (listener.html)
- Visualiseur audio canvas avec couleurs par zone
- Message de bienvenue à la connexion joueur
- Endpoint public `/api/public/status`

### v1.0.2
- Multi-zones WorldGuard indépendantes
- Routing automatique par zone (PlayerMoveEvent)
- Écoute navigateur en direct (WebAudio API)
- Crossfade aléatoire 2-5s entre morceaux
- Pause/Resume à la position exacte
- Cadence 20ms exact (anti-saccade)

### v1.0.1
- Playlists nommées avec enchaînement auto
- Panel redesigné

### v1.0.0
- Version initiale : WebSocket PCM → Simple Voice Chat
- Lecture MP3 et flux Icecast/OBS

---

## 📄 Licence

MIT
