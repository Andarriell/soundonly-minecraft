package com.soundonly.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.soundonly.SoundOnlyPlugin;
import com.soundonly.voice.VoicechatIntegration;
import com.soundonly.worldguard.WorldGuardFilter;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.logging.Logger;

public class AudioWebSocketServer extends WebSocketServer {

    private static final Gson GSON = new Gson();

    private final SoundOnlyPlugin plugin;
    private final VoicechatIntegration voicechat;
    private final WorldGuardFilter worldGuardFilter;
    private final Logger log;

    public AudioWebSocketServer(InetSocketAddress address,
                                SoundOnlyPlugin plugin,
                                VoicechatIntegration voicechat,
                                WorldGuardFilter worldGuardFilter) {
        super(address);
        this.plugin           = plugin;
        this.voicechat        = voicechat;
        this.worldGuardFilter = worldGuardFilter;
        this.log              = plugin.getLogger();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        log.info("Client WebSocket connecté : " + conn.getRemoteSocketAddress());
        conn.send(buildStatus("connected", "SoundOnly WebSocket prêt."));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        log.info("Client WebSocket déconnecté (" + code + ")");
        // Ne pas stopper le streaming — le panel se reconnecte
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.warning("Erreur WebSocket : " + ex.getMessage());
    }

    @Override
    public void onStart() {
        log.info("Serveur WebSocket en écoute sur " + getAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        JsonObject json;
        try {
            json = GSON.fromJson(message, JsonObject.class);
        } catch (Exception e) {
            return;
        }

        String type = json.has("type") ? json.get("type").getAsString() : "";

        switch (type) {
            case "ping" -> conn.send(buildPong());
            case "pong" -> {}

            case "voice_config" -> {
                boolean enabled = json.has("enabled") && json.get("enabled").getAsBoolean();
                if (!enabled) {
                    // Stop le streaming sans toucher aux sessions VC
                    voicechat.stopStreaming();
                    log.info("Voice config : streaming désactivé.");
                    conn.send(buildStatus("stopped", "Streaming arrêté."));
                } else {
                    // Juste logger la config, pas besoin de recréer les sessions
                    String channelType = json.has("channel_type") ? json.get("channel_type").getAsString() : "static";
                    int    distance    = json.has("distance")     ? json.get("distance").getAsInt()         : 48;
                    String zone        = json.has("zone")         ? json.get("zone").getAsString()           : "global";
                    log.info("Voice config : type=" + channelType + " dist=" + distance + " zone=" + zone);
                    conn.send(buildStatus("configured", "Config appliquée."));
                }
            }

            case "voice_audio" -> {
                if (!json.has("data")) return;
                String codec   = json.has("codec") ? json.get("codec").getAsString() : "pcm";
                String b64data = json.get("data").getAsString();
                byte[] raw;
                try {
                    raw = Base64.getDecoder().decode(b64data);
                } catch (IllegalArgumentException e) {
                    return;
                }
                if ("pcm".equals(codec)) {
                    voicechat.broadcastPcm(raw, worldGuardFilter);
                } else if ("opus".equals(codec)) {
                    voicechat.broadcastOpus(raw, worldGuardFilter);
                }
            }

            case "get_voice_status" -> conn.send(buildStatus(
                    voicechat.isStreaming() ? "streaming" : "idle",
                    "Clients connectés : " + getConnections().size()
            ));
        }
    }

    private String buildPong() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "pong");
        o.addProperty("timestamp", System.currentTimeMillis());
        return GSON.toJson(o);
    }

    private String buildStatus(String status, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "status");
        o.addProperty("status", status);
        o.addProperty("message", message);
        o.addProperty("timestamp", System.currentTimeMillis());
        return GSON.toJson(o);
    }
}
