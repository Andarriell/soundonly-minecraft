require("dotenv").config();

const express             = require("express");
const path                = require("path");
const fs                  = require("fs");
const { spawn }           = require("child_process");
const WebSocket           = require("ws");
const { WebSocketServer } = require("ws");
const multer              = require("multer");

const PORT           = Number(process.env.PANEL_PORT   || 3000);
const PANEL_PASSWORD = process.env.PANEL_PASSWORD      || "change-moi";
const MC_WS_URL      = process.env.MC_WS_URL           || "ws://127.0.0.1:8765";
const PANEL_URL      = process.env.PANEL_URL           || "";
const MUSIC_DIR      = path.join(__dirname, "music");
const PUBLIC_DIR     = path.join(__dirname, "public");
const ZONES_FILE     = path.join(__dirname, "zones.json");
const PLAYLISTS_FILE = path.join(__dirname, "playlists.json");
const FRAME_SIZE     = 1920;
const HEARTBEAT_MS   = 10_000;
const MAX_LOGS       = 300;
const AUDIO_EXTS     = /\.(mp3|wav|ogg|flac|m4a)$/i;
const DEFAULT_ZONES  = ["main_stage", "esprit", "vegetal", "futuriste"];

const ZONE_LABELS = {
  "main_stage": "Main Stage",
  "esprit":     "Esprit",
  "vegetal":    "Vegetal",
  "futuriste":  "Futuriste",
};
function zoneLabel(name) { return ZONE_LABELS[name] || name; }

let mcWs           = null;
let heartbeatTimer = null;
const logs         = [];
const zoneStates   = new Map();
const browserClients = new Map();

if (!fs.existsSync(MUSIC_DIR)) fs.mkdirSync(MUSIC_DIR, { recursive: true });

function createZoneState(name) {
  return {
    name,
    ffmpegProc:     null,
    isStreaming:    false,
    isPaused:       false,
    pausedAt:       0,
    streamName:     null,
    streamFile:     null,
    volume:         1.0,
    activePlaylist: null,
    playlistIndex:  0,
    playlistOrder:  [],
    crossfadeTimer: null,
    frameBuffer:    [],
    senderInterval: null,
  };
}

function loadZoneNames() {
  try {
    if (fs.existsSync(ZONES_FILE))
      return JSON.parse(fs.readFileSync(ZONES_FILE, "utf8"));
  } catch (_) {}
  return [];
}

function saveZoneNames() {
  fs.writeFileSync(ZONES_FILE, JSON.stringify([...zoneStates.keys()], null, 2));
}

function loadPlaylists() {
  try {
    if (fs.existsSync(PLAYLISTS_FILE))
      return JSON.parse(fs.readFileSync(PLAYLISTS_FILE, "utf8"));
  } catch (_) {}
  return {};
}

function savePlaylists(data) {
  fs.writeFileSync(PLAYLISTS_FILE, JSON.stringify(data, null, 2), "utf8");
}

const saved = loadZoneNames();
for (const name of (saved.length ? saved : DEFAULT_ZONES)) {
  zoneStates.set(name, createZoneState(name));
}

function log(msg) {
  const line = "[" + new Date().toLocaleTimeString("fr-FR") + "] " + msg;
  console.log(line);
  logs.push(line);
  if (logs.length > MAX_LOGS) logs.shift();
}

function checkAuth(req, res, next) {
  const token = String(req.headers["authorization"] || "").replace(/^Bearer\s+/i, "").trim();
  if (token !== String(PANEL_PASSWORD).trim())
    return res.status(401).json({ error: "Non autorise" });
  next();
}

function safeName(name) {
  const base = path.basename(name || "");
  if (base.includes("..") || base.includes("/") || base.includes("\\")) return "";
  return base;
}

function stopHeartbeat() {
  if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
}

function startHeartbeat(ws) {
  stopHeartbeat();
  heartbeatTimer = setInterval(function() {
    if (ws && ws.readyState === WebSocket.OPEN)
      ws.send(JSON.stringify({ type: "pong", timestamp: Date.now() }));
  }, HEARTBEAT_MS);
}

function connectMinecraft() {
  return new Promise(function(resolve, reject) {
    if (mcWs && mcWs.readyState === WebSocket.OPEN) return resolve(mcWs);
    log("Connexion WebSocket -> " + MC_WS_URL);
    const ws = new WebSocket(MC_WS_URL);
    mcWs = ws;
    const timeout = setTimeout(function() { reject(new Error("Timeout WebSocket")); }, 8000);
    ws.on("open", function() {
      clearTimeout(timeout);
      log("Connecte au plugin Minecraft OK");
      startHeartbeat(ws);
      // Envoie la config de bienvenue au plugin
      if (PANEL_URL) {
        ws.send(JSON.stringify({
          type: "welcome_config",
          url:  PANEL_URL + "/listener.html"
        }));
      }
      resolve(ws);
    });
    ws.on("message", function(data) {
      try {
        const msg = JSON.parse(data.toString());
        if (msg.type === "ping") ws.send(JSON.stringify({ type: "pong", timestamp: Date.now() }));
      } catch (_) {}
    });
    ws.on("close", function(code) {
      log("WebSocket ferme (code " + code + ")");
      stopHeartbeat();
      if (mcWs === ws) mcWs = null;
      for (const z of zoneStates.values()) killFfmpeg(z);
    });
    ws.on("error", function(err) {
      clearTimeout(timeout);
      log("Erreur WebSocket : " + err.message);
      reject(err);
    });
  });
}

function broadcastToBrowser(zoneName, frame) {
  const clients = browserClients.get(zoneName);
  if (!clients || clients.size === 0) return;
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      try { client.send(frame); } catch (_) {}
    }
  }
}

function startFrameSender(zone, ws) {
  if (zone.senderInterval) { clearInterval(zone.senderInterval); zone.senderInterval = null; }
  zone.frameBuffer = [];
  zone.senderInterval = setInterval(function() {
    const frame = zone.frameBuffer.shift();
    if (!frame) return;
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        type: "voice_audio",
        zone: zone.name,
        codec: "pcm",
        data: frame.toString("base64")
      }));
    }
    broadcastToBrowser(zone.name, frame);
  }, 20);
}

function stopFrameSender(zone) {
  if (zone.senderInterval) { clearInterval(zone.senderInterval); zone.senderInterval = null; }
  zone.frameBuffer = [];
}

function killFfmpeg(zone) {
  if (zone.crossfadeTimer) { clearTimeout(zone.crossfadeTimer); zone.crossfadeTimer = null; }
  stopFrameSender(zone);
  if (zone.ffmpegProc) { zone.ffmpegProc.kill("SIGTERM"); zone.ffmpegProc = null; }
  zone.isStreaming = false;
  zone.streamName  = null;
}

function stopZone(zone) {
  killFfmpeg(zone);
  zone.isPaused       = false;
  zone.pausedAt       = 0;
  zone.streamFile     = null;
  zone.activePlaylist = null;
  zone.playlistIndex  = 0;
  zone.playlistOrder  = [];
  if (mcWs && mcWs.readyState === WebSocket.OPEN)
    mcWs.send(JSON.stringify({ type: "voice_config", zone: zone.name, enabled: false }));
  log("[" + zone.name + "] Arrete.");
}

function getDuration(filepath) {
  return new Promise(function(resolve, reject) {
    const proc = spawn("ffprobe", [
      "-v", "error", "-show_entries", "format=duration",
      "-of", "default=noprint_wrappers=1:nokey=1", filepath
    ]);
    let out = "";
    proc.stdout.on("data", function(d) { out += d.toString(); });
    proc.on("close", function() {
      const dur = parseFloat(out.trim());
      if (!isNaN(dur)) resolve(dur);
      else reject(new Error("Duree inconnue"));
    });
    proc.on("error", reject);
  });
}

function randomCrossfade() { return Math.floor(Math.random() * 4) + 2; }

async function startAudio(zone, input, name, isLive, xfadeDuration, onEnd) {
  if (isLive === undefined) isLive = false;
  if (xfadeDuration === undefined) xfadeDuration = 0;
  if (onEnd === undefined) onEnd = null;

  killFfmpeg(zone);
  zone.isPaused   = false;
  zone.pausedAt   = 0;
  zone.streamFile = isLive ? null : input;

  const ws = await connectMinecraft();
  ws.send(JSON.stringify({
    type: "voice_config", zone: zone.name,
    enabled: true, channel_type: "static", distance: 100,
    track: name
  }));

  log("[" + zone.name + "] Lecture : " + name);

  const args = [
    ...(isLive ? [] : ["-re"]),
    "-i", input,
    "-filter:a", "volume=" + zone.volume,
    "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "1", "-ar", "48000", "-"
  ];

  startFrameSender(zone, ws);

  const proc = spawn("ffmpeg", args, { stdio: ["ignore", "pipe", "pipe"] });
  zone.ffmpegProc = proc;
  zone.isStreaming = true;
  zone.streamName  = name;

  const startTime = Date.now();

  if (!isLive && xfadeDuration > 0 && onEnd) {
    getDuration(input).then(function(duration) {
      if (duration > xfadeDuration + 2) {
        const delay = (duration - xfadeDuration) * 1000;
        zone.crossfadeTimer = setTimeout(function() {
          log("[" + zone.name + "] Crossfade -> suivant (" + xfadeDuration + "s)");
          onEnd();
        }, delay);
      }
    }).catch(function() {});
  }

  let buf = Buffer.alloc(0);
  proc.stdout.on("data", function(chunk) {
    if (!zone.isStreaming) return;
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= FRAME_SIZE) {
      const frame = buf.subarray(0, FRAME_SIZE);
      buf = buf.subarray(FRAME_SIZE);
      if (zone.frameBuffer.length < 200)
        zone.frameBuffer.push(Buffer.from(frame));
    }
    zone.pausedAt = (Date.now() - startTime) / 1000;
  });

  proc.stderr.on("data", function(d) {
    const t = d.toString().trim();
    if (t && (t.startsWith("Error") || t.includes("Invalid") || t.includes("No such")))
      log("[" + zone.name + "] FFmpeg ERR: " + t);
  });

  proc.on("close", function(code) {
    if (zone.crossfadeTimer) { clearTimeout(zone.crossfadeTimer); zone.crossfadeTimer = null; }
    if (zone.isPaused) return;
    if (zone.ffmpegProc === proc) {
      zone.isStreaming = false;
      zone.streamName  = null;
      zone.ffmpegProc  = null;
    }
    if (code === 0) {
      log("[" + zone.name + "] Termine : " + name);
      if (onEnd && xfadeDuration === 0) onEnd();
    } else if (code !== 255 && code !== null) {
      log("[" + zone.name + "] FFmpeg code " + code);
    }
  });

  proc.on("error", function(err) {
    log("[" + zone.name + "] Erreur FFmpeg : " + err.message);
    if (zone.ffmpegProc === proc) {
      zone.isStreaming = false;
      zone.streamName  = null;
      zone.ffmpegProc  = null;
    }
  });
}

function pauseZone(zone) {
  if (!zone.isStreaming || zone.isPaused || !zone.streamFile) return false;
  zone.isPaused = true;
  if (zone.crossfadeTimer) { clearTimeout(zone.crossfadeTimer); zone.crossfadeTimer = null; }
  stopFrameSender(zone);
  if (zone.ffmpegProc) { zone.ffmpegProc.kill("SIGTERM"); zone.ffmpegProc = null; }
  zone.isStreaming = false;
  log("[" + zone.name + "] Pause a " + zone.pausedAt.toFixed(1) + "s");
  return true;
}

async function resumeZone(zone) {
  if (!zone.isPaused || !zone.streamFile) return false;
  const seekPos = zone.pausedAt;
  zone.isPaused = false;
  log("[" + zone.name + "] Reprise depuis " + seekPos.toFixed(1) + "s");

  const ws = await connectMinecraft();
  ws.send(JSON.stringify({
    type: "voice_config", zone: zone.name,
    enabled: true, channel_type: "static", distance: 100
  }));

  const args = [
    "-re", "-ss", seekPos.toFixed(2),
    "-i", zone.streamFile,
    "-filter:a", "volume=" + zone.volume + ",afade=t=in:st=0:d=1",
    "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "1", "-ar", "48000", "-"
  ];

  startFrameSender(zone, ws);

  const proc = spawn("ffmpeg", args, { stdio: ["ignore", "pipe", "pipe"] });
  zone.ffmpegProc = proc;
  zone.isStreaming = true;
  const resumeStart = Date.now();

  let buf = Buffer.alloc(0);
  proc.stdout.on("data", function(chunk) {
    if (!zone.isStreaming) return;
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= FRAME_SIZE) {
      const frame = buf.subarray(0, FRAME_SIZE);
      buf = buf.subarray(FRAME_SIZE);
      if (zone.frameBuffer.length < 200)
        zone.frameBuffer.push(Buffer.from(frame));
    }
    zone.pausedAt = seekPos + (Date.now() - resumeStart) / 1000;
  });

  proc.on("close", function(code) {
    if (zone.isPaused) return;
    if (zone.ffmpegProc === proc) {
      zone.isStreaming = false;
      zone.streamName  = null;
      zone.ffmpegProc  = null;
    }
    if (code === 0 && zone.activePlaylist)
      playPlaylistAt(zone, zone.activePlaylist, zone.playlistIndex + 1);
  });

  proc.on("error", function(err) {
    log("[" + zone.name + "] Erreur resume : " + err.message);
    if (zone.ffmpegProc === proc) { zone.isStreaming = false; zone.ffmpegProc = null; }
  });

  return true;
}

function buildPlaylistOrder(tracks) { return tracks.map(function(_, i) { return i; }); }

async function playPlaylistAt(zone, plName, index) {
  const playlists = loadPlaylists();
  const pl = playlists[plName];
  if (!pl || !pl.tracks || pl.tracks.length === 0) return;

  if (index >= zone.playlistOrder.length) {
    log("[" + zone.name + "] Playlist terminee - reboucle.");
    zone.playlistOrder = buildPlaylistOrder(pl.tracks);
    index = 0;
  }

  zone.playlistIndex  = index;
  zone.activePlaylist = plName;

  const file = pl.tracks[zone.playlistOrder[index]];
  const fp   = path.join(MUSIC_DIR, file);

  if (!fs.existsSync(fp)) {
    log("[" + zone.name + "] Fichier manquant : " + file + " - suivant.");
    return playPlaylistAt(zone, plName, index + 1);
  }

  const xfade = randomCrossfade();
  await startAudio(zone, fp, file, false, xfade, function() {
    if (zone.activePlaylist === plName && !zone.isPaused)
      playPlaylistAt(zone, plName, zone.playlistIndex + 1);
  });
}

async function startPlaylist(zone, plName) {
  const playlists = loadPlaylists();
  const pl = playlists[plName];
  if (!pl || !pl.tracks || pl.tracks.length === 0)
    throw new Error("Playlist introuvable : " + plName);
  zone.playlistOrder = buildPlaylistOrder(pl.tracks);
  log("[" + zone.name + "] Playlist : " + plName + " (" + pl.tracks.length + " morceaux)");
  await playPlaylistAt(zone, plName, 0);
}

const app = express();
app.use(express.json());
app.use(express.static(PUBLIC_DIR));
const upload = multer({ dest: MUSIC_DIR, limits: { fileSize: 500 * 1024 * 1024 } });

app.post("/api/login", function(req, res) {
  if (req.body.password === PANEL_PASSWORD) return res.json({ ok: true });
  res.status(401).json({ error: "Mot de passe incorrect" });
});

app.get("/api/status", checkAuth, function(req, res) {
  const zonesStatus = {};
  for (const [name, z] of zoneStates) {
    const pl = z.activePlaylist ? loadPlaylists()[z.activePlaylist] : null;
    zonesStatus[name] = {
      streaming:      z.isStreaming,
      isPaused:       z.isPaused,
      pausedAt:       z.pausedAt,
      streamName:     z.streamName,
      volume:         z.volume,
      activePlaylist: z.activePlaylist,
      playlistIndex:  z.playlistIndex,
      currentTrack:   pl ? pl.tracks[z.playlistOrder[z.playlistIndex]] : z.streamName,
    };
  }
  res.json({
    ok: true,
    wsConnected: mcWs && mcWs.readyState === WebSocket.OPEN,
    mcWsUrl: MC_WS_URL,
    zones: zonesStatus,
    logs
  });
});

app.get("/api/public/status", function(req, res) {
  const zonesPublic = {};
  for (const [name, z] of zoneStates) {
    const pl = z.activePlaylist ? loadPlaylists()[z.activePlaylist] : null;
    zonesPublic[name] = {
      streaming:    z.isStreaming,
      isPaused:     z.isPaused,
      currentTrack: pl ? pl.tracks[z.playlistOrder[z.playlistIndex]] : z.streamName,
      label:        zoneLabel(name),
    };
  }
  res.json({ ok: true, zones: zonesPublic });
});

app.get("/api/zones", checkAuth, function(req, res) {
  res.json({ ok: true, zones: [...zoneStates.keys()] });
});

app.post("/api/zones", checkAuth, function(req, res) {
  const name = (req.body.name || "").trim();
  if (!name) return res.status(400).json({ error: "Nom invalide" });
  if (zoneStates.has(name)) return res.status(409).json({ error: "Zone deja existante" });
  zoneStates.set(name, createZoneState(name));
  saveZoneNames();
  if (mcWs && mcWs.readyState === WebSocket.OPEN)
    mcWs.send(JSON.stringify({ type: "zone_create", zone: name }));
  log("Zone creee : " + name);
  res.json({ ok: true });
});

app.delete("/api/zones/:zone", checkAuth, function(req, res) {
  const name = req.params.zone;
  const zone = zoneStates.get(name);
  if (!zone) return res.status(404).json({ error: "Zone introuvable" });
  stopZone(zone);
  zoneStates.delete(name);
  saveZoneNames();
  if (mcWs && mcWs.readyState === WebSocket.OPEN)
    mcWs.send(JSON.stringify({ type: "zone_remove", zone: name }));
  log("Zone supprimee : " + name);
  res.json({ ok: true });
});

function getZone(req, res) {
  const zone = zoneStates.get(req.params.zone);
  if (!zone) { res.status(404).json({ error: "Zone introuvable" }); return null; }
  return zone;
}

app.post("/api/zones/:zone/play", checkAuth, async function(req, res) {
  const zone = getZone(req, res); if (!zone) return;
  try {
    zone.activePlaylist = null;
    const file = safeName(req.body.file);
    if (!file) return res.status(400).json({ error: "Nom invalide" });
    const fp = path.join(MUSIC_DIR, file);
    if (!fs.existsSync(fp)) return res.status(404).json({ error: "Fichier introuvable" });
    await startAudio(zone, fp, file, false, 0, null);
    res.json({ ok: true });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

app.post("/api/zones/:zone/stream-url", checkAuth, async function(req, res) {
  const zone = getZone(req, res); if (!zone) return;
  try {
    zone.activePlaylist = null;
    const url = req.body.url;
    if (!url || !/^https?:\/\//i.test(url)) return res.status(400).json({ error: "URL invalide" });
    await startAudio(zone, url, "Flux : " + url, true, 0, null);
    res.json({ ok: true });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

app.post("/api/zones/:zone/stop", checkAuth, function(req, res) {
  const zone = getZone(req, res); if (!zone) return;
  stopZone(zone); res.json({ ok: true });
});

app.post("/api/zones/:zone/pause", checkAuth, function(req, res) {
  const zone = getZone(req, res); if (!zone) return;
  if (!pauseZone(zone)) return res.status(400).json({ error: "Rien a mettre en pause" });
  res.json({ ok: true, pausedAt: zone.pausedAt });
});

app.post("/api/zones/:zone/resume", checkAuth, async function(req, res) {
  const zone = getZone(req, res); if (!zone) return;
  try {
    if (!await resumeZone(zone)) return res.status(400).json({ error: "Rien a reprendre" });
    res.json({ ok: true });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

app.post("/api/zones/:zone/volume", checkAuth, function(req, res) {
  const zone = getZone(req, res); if (!zone) return;
  const v = Number(req.body.volume);
  if (isNaN(v) || v < 0 || v > 3) return res.status(400).json({ error: "Volume 0-3" });
  zone.volume = v;
  log("[" + zone.name + "] Volume -> " + Math.round(v * 100) + "%");
  res.json({ ok: true, volume: v });
});

app.post("/api/zones/:zone/playlist/play", checkAuth, async function(req, res) {
  const zone = getZone(req, res); if (!zone) return;
  try { await startPlaylist(zone, req.body.playlist); res.json({ ok: true }); }
  catch (err) { res.status(500).json({ error: err.message }); }
});

app.post("/api/zones/:zone/playlist/next", checkAuth, async function(req, res) {
  const zone = getZone(req, res); if (!zone) return;
  if (!zone.activePlaylist) return res.status(400).json({ error: "Aucune playlist active" });
  try {
    await playPlaylistAt(zone, zone.activePlaylist, zone.playlistIndex + 1);
    res.json({ ok: true });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

app.post("/api/zones/:zone/playlist/prev", checkAuth, async function(req, res) {
  const zone = getZone(req, res); if (!zone) return;
  if (!zone.activePlaylist) return res.status(400).json({ error: "Aucune playlist active" });
  try {
    await playPlaylistAt(zone, zone.activePlaylist, Math.max(0, zone.playlistIndex - 1));
    res.json({ ok: true });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

app.get("/api/music", checkAuth, function(req, res) {
  const files = fs.readdirSync(MUSIC_DIR).filter(function(f) { return AUDIO_EXTS.test(f); }).sort();
  res.json({ ok: true, files });
});

app.post("/api/upload", checkAuth, upload.single("file"), function(req, res) {
  if (!req.file) return res.status(400).json({ error: "Aucun fichier recu" });
  const dest = path.join(MUSIC_DIR, req.file.originalname);
  fs.renameSync(req.file.path, dest);
  log("Fichier uploade : " + req.file.originalname);
  res.json({ ok: true, file: req.file.originalname });
});

app.delete("/api/music/:file", checkAuth, function(req, res) {
  const file = safeName(decodeURIComponent(req.params.file));
  if (!file) return res.status(400).json({ error: "Nom invalide" });
  const fp = path.join(MUSIC_DIR, file);
  if (!fs.existsSync(fp)) return res.status(404).json({ error: "Fichier introuvable" });
  for (const z of zoneStates.values()) if (z.streamName === file) stopZone(z);
  fs.unlinkSync(fp);
  log("Fichier supprime : " + file);
  res.json({ ok: true });
});

app.get("/api/playlists", checkAuth, function(req, res) {
  res.json({ ok: true, playlists: loadPlaylists() });
});

app.post("/api/playlists", checkAuth, function(req, res) {
  const name   = req.body.name;
  const tracks = req.body.tracks;
  if (!name || !name.trim()) return res.status(400).json({ error: "Nom invalide" });
  if (!Array.isArray(tracks)) return res.status(400).json({ error: "tracks invalide" });
  const data = loadPlaylists();
  data[name.trim()] = { name: name.trim(), tracks };
  savePlaylists(data);
  log("Playlist sauvegardee : " + name);
  res.json({ ok: true });
});

app.delete("/api/playlists/:name", checkAuth, function(req, res) {
  const name = decodeURIComponent(req.params.name);
  const data = loadPlaylists();
  if (!data[name]) return res.status(404).json({ error: "Playlist introuvable" });
  delete data[name];
  savePlaylists(data);
  res.json({ ok: true });
});

app.post("/api/test-minecraft", checkAuth, async function(req, res) {
  try {
    const ws = await connectMinecraft();
    ws.send(JSON.stringify({ type: "pong", timestamp: Date.now() }));
    res.json({ ok: true, message: "Plugin joignable OK", url: MC_WS_URL });
  } catch (err) { res.status(500).json({ ok: false, error: err.message }); }
});

const httpServer = app.listen(PORT, "0.0.0.0", function() {
  log("Audio Panel -> http://0.0.0.0:" + PORT);
  log("WebSocket cible -> " + MC_WS_URL);
  log("Zones : " + [...zoneStates.keys()].join(", "));
});

const browserWss = new WebSocketServer({ server: httpServer, path: "/listen" });

browserWss.on("connection", function(ws, req) {
  const url  = new URL(req.url, "http://localhost:" + PORT);
  const zone = url.searchParams.get("zone") || "global";
  if (!browserClients.has(zone)) browserClients.set(zone, new Set());
  browserClients.get(zone).add(ws);
  log("[Browser] Client connecte sur zone : " + zone);
  ws.on("close", function() {
    browserClients.get(zone) && browserClients.get(zone).delete(ws);
  });
  ws.on("error", function() {
    browserClients.get(zone) && browserClients.get(zone).delete(ws);
  });
});
