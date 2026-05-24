require("dotenv").config();

const express = require("express");
const path    = require("path");
const fs      = require("fs");
const { spawn } = require("child_process");
const WebSocket  = require("ws");
const multer     = require("multer");

// ─── Config ───────────────────────────────────────────────────────────────────
const PORT           = Number(process.env.PANEL_PORT   || 3000);
const PANEL_PASSWORD = process.env.PANEL_PASSWORD      || "change-moi";
const MC_WS_URL      = process.env.MC_WS_URL           || "ws://127.0.0.1:8765";
const MUSIC_DIR      = path.join(__dirname, "music");
const PUBLIC_DIR     = path.join(__dirname, "public");
const PLAYLISTS_FILE = path.join(__dirname, "playlists.json");
const FRAME_SIZE     = 1920;
const HEARTBEAT_MS   = 10_000;
const MAX_LOGS       = 200;
const AUDIO_EXTS     = /\.(mp3|wav|ogg|flac|m4a)$/i;

// ─── State ────────────────────────────────────────────────────────────────────
let ffmpegProc      = null;
let mcWs            = null;
let heartbeatTimer  = null;
let currentVolume   = 1.0;
let isStreaming      = false;
let streamName      = null;

// Playlist state
let activePlaylist   = null;  // nom de la playlist en cours
let playlistIndex    = 0;     // index du morceau en cours
let playlistShuffle  = false;
let playlistOrder    = [];    // ordre de lecture (indices)

const logs = [];

if (!fs.existsSync(MUSIC_DIR)) fs.mkdirSync(MUSIC_DIR, { recursive: true });

// ─── Playlists persistence ────────────────────────────────────────────────────
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

// ─── Logging ──────────────────────────────────────────────────────────────────
function log(msg) {
  const line = `[${new Date().toLocaleTimeString("fr-FR")}] ${msg}`;
  console.log(line);
  logs.push(line);
  if (logs.length > MAX_LOGS) logs.shift();
}

// ─── Auth ─────────────────────────────────────────────────────────────────────
function checkAuth(req, res, next) {
  const token = String(req.headers["authorization"] || "").replace(/^Bearer\s+/i, "").trim();
  if (token !== String(PANEL_PASSWORD).trim())
    return res.status(401).json({ error: "Non autorisé" });
  next();
}

function safeName(name) {
  const base = path.basename(name || "");
  if (base.includes("..") || base.includes("/") || base.includes("\\")) return "";
  return base;
}

// ─── Heartbeat ────────────────────────────────────────────────────────────────
function stopHeartbeat() {
  if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
}
function startHeartbeat(ws) {
  stopHeartbeat();
  heartbeatTimer = setInterval(() => {
    if (ws?.readyState === WebSocket.OPEN)
      ws.send(JSON.stringify({ type: "pong", timestamp: Date.now() }));
  }, HEARTBEAT_MS);
}

// ─── WebSocket Minecraft ──────────────────────────────────────────────────────
function connectMinecraft() {
  return new Promise((resolve, reject) => {
    if (mcWs?.readyState === WebSocket.OPEN) return resolve(mcWs);
    log(`Connexion WebSocket → ${MC_WS_URL}`);
    const ws = new WebSocket(MC_WS_URL);
    mcWs = ws;
    const timeout = setTimeout(() => reject(new Error("Timeout connexion WebSocket")), 8000);
    ws.on("open", () => {
      clearTimeout(timeout);
      log("Connecté au plugin Minecraft ✓");
      startHeartbeat(ws);
      resolve(ws);
    });
    ws.on("message", (data) => {
      try {
        const msg = JSON.parse(data.toString());
        if (msg.type === "ping") ws.send(JSON.stringify({ type: "pong", timestamp: Date.now() }));
      } catch (_) {}
    });
    ws.on("close", (code) => {
      log(`WebSocket Minecraft fermé (code ${code})`);
      stopHeartbeat();
      if (mcWs === ws) mcWs = null;
      if (isStreaming) {
        isStreaming = false; streamName = null;
        if (ffmpegProc) { ffmpegProc.kill("SIGTERM"); ffmpegProc = null; }
      }
    });
    ws.on("error", (err) => { clearTimeout(timeout); log(`Erreur WebSocket : ${err.message}`); reject(err); });
  });
}

// ─── Stream ───────────────────────────────────────────────────────────────────
function killFfmpeg() {
  if (ffmpegProc) { ffmpegProc.kill("SIGTERM"); ffmpegProc = null; }
  isStreaming = false;
  streamName  = null;
}

function stopStream() {
  killFfmpeg();
  activePlaylist = null;
  playlistIndex  = 0;
  playlistOrder  = [];
  if (mcWs?.readyState === WebSocket.OPEN)
    mcWs.send(JSON.stringify({ type: "voice_config", enabled: false }));
  log("Stream arrêté.");
}

async function startAudio(input, name, isLive = false, onEnd = null) {
  killFfmpeg();
  const ws = await connectMinecraft();
  ws.send(JSON.stringify({ type: "voice_config", enabled: true, channel_type: "static", distance: 100, zone: "main" }));
  log(`Démarrage FFmpeg : ${name}`);

  const args = [
    ...(isLive ? [] : ["-re"]),
    "-i", input,
    "-filter:a", `volume=${currentVolume}`,
    "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "1", "-ar", "48000", "-"
  ];

  const proc = spawn("ffmpeg", args, { stdio: ["ignore", "pipe", "pipe"] });
  ffmpegProc = proc;
  isStreaming = true;
  streamName  = name;

  let buf = Buffer.alloc(0);
  proc.stdout.on("data", (chunk) => {
    if (!isStreaming) return;
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= FRAME_SIZE) {
      const frame = buf.subarray(0, FRAME_SIZE);
      buf = buf.subarray(FRAME_SIZE);
      if (ws.readyState === WebSocket.OPEN)
        ws.send(JSON.stringify({ type: "voice_audio", codec: "pcm", data: frame.toString("base64") }));
    }
  });

  proc.stderr.on("data", (d) => {
    const t = d.toString().trim();
    if (t && (t.startsWith("Error") || t.includes("Invalid") || t.includes("No such")))
      log(`FFmpeg ERR: ${t}`);
  });

  proc.on("close", (code) => {
    log(`FFmpeg terminé (code ${code})`);
    isStreaming = false; streamName = null; ffmpegProc = null;
    if (code === 0 && onEnd) onEnd(); // → morceau suivant
  });

  proc.on("error", (err) => {
    log(`Erreur FFmpeg : ${err.message}`);
    isStreaming = false; streamName = null; ffmpegProc = null;
  });
}

// ─── Playlist logic ───────────────────────────────────────────────────────────
function buildPlaylistOrder(tracks, shuffle) {
  const indices = tracks.map((_, i) => i);
  if (!shuffle) return indices;
  // Fisher-Yates shuffle
  for (let i = indices.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [indices[i], indices[j]] = [indices[j], indices[i]];
  }
  return indices;
}

async function playPlaylistAt(name, index) {
  const playlists = loadPlaylists();
  const pl = playlists[name];
  if (!pl || !pl.tracks || pl.tracks.length === 0) return;

  if (index >= playlistOrder.length) {
    // Fin de la playlist — reboucle
    log(`Playlist "${name}" terminée — reboucle.`);
    playlistOrder = buildPlaylistOrder(pl.tracks, playlistShuffle);
    index = 0;
  }

  playlistIndex = index;
  const trackIndex = playlistOrder[index];
  const file = pl.tracks[trackIndex];
  const fp   = path.join(MUSIC_DIR, file);

  if (!fs.existsSync(fp)) {
    log(`Fichier manquant dans playlist : ${file} — on passe au suivant.`);
    return playPlaylistAt(name, index + 1);
  }

  activePlaylist = name;
  await startAudio(fp, file, false, () => {
    // Appelé quand FFmpeg termine proprement (code 0)
    if (activePlaylist === name) playPlaylistAt(name, index + 1);
  });
}

async function startPlaylist(name, shuffle = false) {
  const playlists = loadPlaylists();
  const pl = playlists[name];
  if (!pl || !pl.tracks || pl.tracks.length === 0)
    throw new Error(`Playlist "${name}" vide ou introuvable.`);

  playlistShuffle = shuffle;
  playlistOrder   = buildPlaylistOrder(pl.tracks, shuffle);
  log(`Lecture playlist "${name}" (${pl.tracks.length} morceaux, shuffle=${shuffle})`);
  await playPlaylistAt(name, 0);
}

// ─── Express ──────────────────────────────────────────────────────────────────
const app = express();
app.use(express.json());
app.use(express.static(PUBLIC_DIR));

const upload = multer({ dest: MUSIC_DIR, limits: { fileSize: 500 * 1024 * 1024 } });

// Auth
app.post("/api/login", (req, res) => {
  if (req.body.password === PANEL_PASSWORD) return res.json({ ok: true });
  res.status(401).json({ error: "Mot de passe incorrect" });
});

// Status
app.get("/api/status", checkAuth, (req, res) => {
  const playlists = loadPlaylists();
  const pl = activePlaylist ? playlists[activePlaylist] : null;
  const trackIndex = pl ? playlistOrder[playlistIndex] : null;
  res.json({
    ok: true, streaming: isStreaming, streamName, volume: currentVolume,
    mcWsUrl: MC_WS_URL, wsConnected: mcWs?.readyState === WebSocket.OPEN,
    activePlaylist, playlistIndex,
    currentTrack: pl && trackIndex !== undefined ? pl.tracks[trackIndex] : null,
    playlistShuffle, logs
  });
});

// Fichiers
app.get("/api/music", checkAuth, (req, res) => {
  const files = fs.readdirSync(MUSIC_DIR).filter(f => AUDIO_EXTS.test(f)).sort();
  res.json({ ok: true, files });
});

app.post("/api/upload", checkAuth, upload.single("file"), (req, res) => {
  if (!req.file) return res.status(400).json({ error: "Aucun fichier reçu" });
  const dest = path.join(MUSIC_DIR, req.file.originalname);
  fs.renameSync(req.file.path, dest);
  log(`Fichier uploadé : ${req.file.originalname}`);
  res.json({ ok: true, file: req.file.originalname });
});

app.delete("/api/music/:file", checkAuth, (req, res) => {
  const file = safeName(decodeURIComponent(req.params.file));
  if (!file) return res.status(400).json({ error: "Nom invalide" });
  const fp = path.join(MUSIC_DIR, file);
  if (!fs.existsSync(fp)) return res.status(404).json({ error: "Fichier introuvable" });
  if (streamName === file) stopStream();
  fs.unlinkSync(fp);
  log(`Fichier supprimé : ${file}`);
  res.json({ ok: true });
});

// Playback fichier unique
app.post("/api/play", checkAuth, async (req, res) => {
  try {
    activePlaylist = null;
    const file = safeName(req.body.file);
    if (!file) return res.status(400).json({ error: "Nom invalide" });
    const fp = path.join(MUSIC_DIR, file);
    if (!fs.existsSync(fp)) return res.status(404).json({ error: "Fichier introuvable" });
    await startAudio(fp, file, false);
    res.json({ ok: true });
  } catch (err) { log(`Erreur play : ${err.message}`); res.status(500).json({ error: err.message }); }
});

app.post("/api/stream-url", checkAuth, async (req, res) => {
  try {
    activePlaylist = null;
    const url = req.body.url;
    if (!url || !/^https?:\/\//i.test(url)) return res.status(400).json({ error: "URL invalide" });
    await startAudio(url, `Flux : ${url}`, true);
    res.json({ ok: true });
  } catch (err) { log(`Erreur flux : ${err.message}`); res.status(500).json({ error: err.message }); }
});

app.post("/api/stop", checkAuth, (req, res) => { stopStream(); res.json({ ok: true }); });

app.post("/api/volume", checkAuth, (req, res) => {
  const v = Number(req.body.volume);
  if (isNaN(v) || v < 0 || v > 3) return res.status(400).json({ error: "Volume 0-3" });
  currentVolume = v;
  log(`Volume → ${Math.round(v * 100)}%`);
  res.json({ ok: true, volume: v });
});

app.post("/api/test-minecraft", checkAuth, async (req, res) => {
  try {
    const ws = await connectMinecraft();
    ws.send(JSON.stringify({ type: "pong", timestamp: Date.now() }));
    res.json({ ok: true, message: "Plugin Minecraft joignable ✓", url: MC_WS_URL });
  } catch (err) { res.status(500).json({ ok: false, error: err.message }); }
});

// ─── Playlists API ────────────────────────────────────────────────────────────

// Liste toutes les playlists
app.get("/api/playlists", checkAuth, (req, res) => {
  res.json({ ok: true, playlists: loadPlaylists() });
});

// Créer ou mettre à jour une playlist
app.post("/api/playlists", checkAuth, (req, res) => {
  const { name, tracks } = req.body;
  if (!name || typeof name !== "string" || name.trim() === "")
    return res.status(400).json({ error: "Nom invalide" });
  if (!Array.isArray(tracks))
    return res.status(400).json({ error: "tracks doit être un tableau" });
  const data = loadPlaylists();
  data[name.trim()] = { name: name.trim(), tracks };
  savePlaylists(data);
  log(`Playlist "${name}" sauvegardée (${tracks.length} morceaux).`);
  res.json({ ok: true });
});

// Supprimer une playlist
app.delete("/api/playlists/:name", checkAuth, (req, res) => {
  const name = decodeURIComponent(req.params.name);
  const data = loadPlaylists();
  if (!data[name]) return res.status(404).json({ error: "Playlist introuvable" });
  delete data[name];
  savePlaylists(data);
  log(`Playlist "${name}" supprimée.`);
  res.json({ ok: true });
});

// Lancer une playlist
app.post("/api/playlists/:name/play", checkAuth, async (req, res) => {
  try {
    const name    = decodeURIComponent(req.params.name);
    const shuffle = !!req.body.shuffle;
    await startPlaylist(name, shuffle);
    res.json({ ok: true });
  } catch (err) { log(`Erreur playlist : ${err.message}`); res.status(500).json({ error: err.message }); }
});

// Morceau suivant
app.post("/api/playlists/next", checkAuth, async (req, res) => {
  if (!activePlaylist) return res.status(400).json({ error: "Aucune playlist active" });
  try {
    killFfmpeg();
    await playPlaylistAt(activePlaylist, playlistIndex + 1);
    res.json({ ok: true });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

// Morceau précédent
app.post("/api/playlists/prev", checkAuth, async (req, res) => {
  if (!activePlaylist) return res.status(400).json({ error: "Aucune playlist active" });
  try {
    killFfmpeg();
    const prev = Math.max(0, playlistIndex - 1);
    await playPlaylistAt(activePlaylist, prev);
    res.json({ ok: true });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

app.listen(PORT, "0.0.0.0", () => {
  log(`Audio Panel → http://0.0.0.0:${PORT}`);
  log(`Plugin WebSocket cible → ${MC_WS_URL}`);
});
