# 🎛 SoundOnly + Audio Panel

> Diffuse de la musique en temps réel dans Minecraft via **Simple Voice Chat** — contrôlé depuis un panel web.

---

## 📋 Présentation

**SoundOnly** est un plugin Paper + un panel web Node.js qui permettent de broadcaster de la musique (MP3, flux Icecast/OBS) directement dans les oreilles des joueurs connectés à Simple Voice Chat, sans aucun effet visuel ou décoration — juste le son.

```
[MP3 / Flux Icecast] → [FFmpeg] → [Panel Node.js] → [WebSocket] → [Plugin SoundOnly] → [Simple Voice Chat] → 🎧 Joueurs
```

---

## ✨ Fonctionnalités

- 🎵 Lecture de fichiers audio (MP3, WAV, OGG, FLAC, M4A)
- 📻 Flux live Icecast / OBS
- 📂 Système de playlists nommées avec lecture automatique enchaînée
- 🔀 Mode shuffle
- ⏮ ⏭ Navigation entre morceaux
- 🔊 Contrôle du volume via gain FFmpeg
- 🗺 Filtrage par région WorldGuard (optionnel)
- 🔌 WebSocket dédié — panel séparé du serveur Minecraft
- 🖥 Interface web épurée, responsive

---

## 🧱 Prérequis

### Serveur Minecraft
| Composant | Version |
|-----------|---------|
| Paper | 1.21.x |
| Simple Voice Chat | 2.6.17+ |
| WorldGuard + WorldEdit | 7.x (optionnel) |

### Machine hébergeant le panel
| Composant | Version |
|-----------|---------|
| Node.js | 18+ |
| FFmpeg | 5+ (`apt install ffmpeg`) |
| npm | 8+ |

---

## 🚀 Installation

### 1. Plugin Minecraft

**Compiler le plugin :**

```bash
# Placer voicechat-bukkit-2.6.17.jar dans lib/
# (téléchargeable sur Modrinth : https://modrinth.com/plugin/simple-voice-chat)
cp voicechat-bukkit-2.6.17.jar final_plugin/lib/voicechat-api-2.6.17.jar

cd final_plugin
mvn package -q
```

**Déployer :**

```bash
cp target/SoundOnly-1.0.0.jar /chemin/vers/plugins/
```

**Redémarrer le serveur** — la config est générée automatiquement dans `plugins/SoundOnly/`.

---

### 2. Panel web

```bash
cd final_panel
npm install
cp .env.example .env
nano .env        # configurer mot de passe et URL WebSocket
npm start
```

Accès depuis le navigateur : `http://<IP-DU-SERVEUR>:3000`

---

## ⚙️ Configuration

### Plugin — `plugins/SoundOnly/config.yml`

```yaml
websocket:
  address: "0.0.0.0"   # écoute sur toutes les interfaces
  port: 8765            # port WebSocket (ouvrir dans le pare-feu)

worldguard:
  enabled: false        # true = filtrage par région
  regions:
    - main_stage        # IDs des régions WorldGuard autorisées
  allow-if-missing: true
  bypass-permission: "soundonly.worldguard.bypass"
```

### Panel — `.env`

```env
# Port HTTP du panel web
PANEL_PORT=3000

# Mot de passe d'accès au panel
PANEL_PASSWORD=change-moi

# URL WebSocket du plugin Minecraft
# Même machine : ws://127.0.0.1:8765
# Machine distante : ws://<IP-MINECRAFT>:8765
MC_WS_URL=ws://127.0.0.1:8765
```

---

## 🔥 Pare-feu

Le port WebSocket (8765) doit être accessible depuis la machine hébergeant le panel.

```bash
# UFW
ufw allow 8765/tcp
ufw allow 3000/tcp   # pour le panel web

# iptables
iptables -A INPUT -p tcp --dport 8765 -j ACCEPT
```

---

## 🖥 Lancement en production (PM2)

```bash
npm install -g pm2
cd final_panel
pm2 start server.js --name audio-panel
pm2 save
pm2 startup
```

---

## 📁 Structure du projet

```
SoundOnly/
├── final_plugin/                  # Sources du plugin Java
│   ├── src/main/java/com/soundonly/
│   │   ├── SoundOnlyPlugin.java        # Classe principale
│   │   ├── websocket/
│   │   │   └── AudioWebSocketServer.java
│   │   ├── voice/
│   │   │   └── VoicechatIntegration.java
│   │   └── worldguard/
│   │       └── WorldGuardFilter.java
│   ├── src/main/resources/
│   │   ├── plugin.yml
│   │   └── config.yml
│   ├── lib/
│   │   └── voicechat-api-2.6.17.jar   # À fournir manuellement
│   └── pom.xml
│
└── final_panel/                   # Panel web Node.js
    ├── server.js                       # Serveur Express + WebSocket
    ├── .env.example                    # Modèle de configuration
    ├── package.json
    ├── music/                          # Fichiers audio uploadés
    ├── playlists.json                  # Playlists sauvegardées (auto)
    └── public/
        └── index.html                  # Interface web
```

---

## 🎮 Commandes in-game

| Commande | Description |
|----------|-------------|
| `/soundonly status` | État du WebSocket, streaming, clients connectés |
| `/soundonly stop` | Arrête le stream en cours |
| `/soundonly reload` | Recharge `config.yml` sans redémarrer |

**Permission requise :** `soundonly.control` (op par défaut)

---

## 🔌 Protocole WebSocket

Le panel communique avec le plugin via WebSocket (JSON).

| Message | Direction | Description |
|---------|-----------|-------------|
| `ping` / `pong` | ↔ | Keepalive |
| `voice_config` | Panel → Plugin | Active/désactive le streaming |
| `voice_audio` | Panel → Plugin | Frame PCM encodée en base64 |
| `get_voice_status` | Panel → Plugin | Demande l'état courant |
| `status` | Plugin → Panel | Réponse état |

**Format audio :** PCM 16-bit little-endian, mono, 48kHz, 1920 bytes par frame (960 samples = 20ms).

---

## 🛠 Dépannage

**"Plugin : erreur" dans le panel**
- Vérifier que le serveur Minecraft tourne
- Vérifier `MC_WS_URL` dans `.env`
- Vérifier que le port 8765 est ouvert
- Chercher dans les logs : `[SoundOnly] WebSocket démarré sur 0.0.0.0:8765`

**Pas de son en jeu**
- Vérifier que Simple Voice Chat est bien connecté (icône en jeu)
- Chercher dans les logs : `[SoundOnly] Session audio ouverte pour <pseudo>`
- Si OpenAudioMc est installé → le désactiver (conflit avec Voice Chat)

**Son saccadé**
- Vérifier la stabilité réseau entre panel et Minecraft
- Éviter de relancer trop rapidement après un stop
- Vérifier que FFmpeg tourne en continu sans interruption

**FFmpeg code 255**
- Normal après un clic sur Stop (arrêt volontaire via SIGTERM)
- Si immédiat au lancement : vérifier que le fichier existe dans `music/`

**Erreur Maven `voicechat-api` introuvable**
- Télécharger `voicechat-bukkit-2.6.17.jar` depuis [Modrinth](https://modrinth.com/plugin/simple-voice-chat)
- Le placer dans `final_plugin/lib/voicechat-api-2.6.17.jar`
- Relancer `mvn package -q`

---

## 📦 Dépendances

### Plugin Java
| Dépendance | Scope |
|------------|-------|
| Paper API 1.21 | provided |
| Simple Voice Chat API 2.6.17 | provided (lib locale) |
| WorldGuard 7.x | provided |
| Java-WebSocket 1.5.6 | shaded |
| Gson 2.11 | shaded |

### Panel Node.js
| Dépendance | Usage |
|------------|-------|
| express | Serveur HTTP |
| ws | Client WebSocket vers Minecraft |
| multer | Upload de fichiers audio |
| dotenv | Variables d'environnement |

---

## 📄 Licence

MIT
