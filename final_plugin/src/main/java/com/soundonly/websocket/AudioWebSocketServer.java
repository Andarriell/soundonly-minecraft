package com.soundonly.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.soundonly.SoundOnlyPlugin;
import com.soundonly.voice.VoicechatIntegration;
import com.soundonly.zone.AudioZone;
import com.soundonly.zone.ZoneManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Logger;

public class AudioWebSocketServer extends WebSocketServer {

    private static final Gson GSON = new Gson();

    private final SoundOnlyPlugin plugin;
    private final VoicechatIntegration voicechat;
    private final ZoneManager zoneManager;
    private final Logger log;

    public AudioWebSocketServer(InetSocketAddress address,
                                SoundOnlyPlugin plugin,
                                VoicechatIntegration voicechat,
                                ZoneManager zoneManager) {
        super(address);
        this.plugin      = plugin;
        this.voicechat   = voicechat;
        this.zoneManager = zoneManager;
        this.log         = plugin.getLogger();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        log.info("Client WebSocket connecte : " + conn.getRemoteSocketAddress());
        conn.send(buildStatus("connected", "SoundOnly WebSocket pret."));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        log.info("Client WebSocket deconnecte (" + code + ")");
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.warning("Erreur WebSocket : " + ex.getMessage());
    }

    @Override
    public void onStart() {
        log.info("Serveur WebSocket en ecoute sur " + getAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        JsonObject json;
        try {
            json = GSON.fromJson(message, JsonObject.class);
        } catch (Exception e) { return; }

        String type = json.has("type") ? json.get("type").getAsString() : "";

        switch (type) {

            case "ping" -> conn.send(buildPong());
            case "pong" -> {}

            case "voice_config" -> {
                boolean enabled  = json.has("enabled") && json.get("enabled").getAsBoolean();
                String  zoneName = json.has("zone") ? json.get("zone").getAsString() : "global";
                if (!enabled) {
                    voicechat.stopZone(zoneName);
                    // Efface le morceau en cours quand on stoppe
                    com.soundonly.zone.AudioZone z = zoneManager.getZone(zoneName);
                    if (z != null) z.setCurrentTrack(null);
                    conn.send(buildStatus("stopped", "Zone " + zoneName + " arretee."));
                } else {
                    com.soundonly.zone.AudioZone zone = zoneManager.getOrCreateZone(zoneName);
                    // Stocke le morceau en cours si fourni
                    if (zone != null && json.has("track")) {
                        zone.setCurrentTrack(json.get("track").getAsString());
                    }
                    conn.send(buildStatus("configured", "Zone " + zoneName + " configuree."));
                }
            }

            case "voice_audio" -> {
                if (!json.has("data")) return;
                String zoneName = json.has("zone") ? json.get("zone").getAsString() : "global";
                String codec    = json.has("codec") ? json.get("codec").getAsString() : "pcm";
                byte[] raw;
                try { raw = Base64.getDecoder().decode(json.get("data").getAsString()); }
                catch (IllegalArgumentException e) { return; }
                if ("pcm".equals(codec))       voicechat.broadcastPcmToZone(zoneName, raw);
                else if ("opus".equals(codec)) voicechat.broadcastOpusToZone(zoneName, raw);
            }

            case "chat_message" -> handleChatMessage(json);

            case "zone_create" -> {
                String zoneName = json.has("zone") ? json.get("zone").getAsString() : null;
                if (zoneName != null && !zoneName.isBlank()) {
                    zoneManager.getOrCreateZone(zoneName);
                    conn.send(buildStatus("zone_created", "Zone creee : " + zoneName));
                }
            }

            case "zone_remove" -> {
                String zoneName = json.has("zone") ? json.get("zone").getAsString() : null;
                if (zoneName != null) {
                    zoneManager.removeZone(zoneName);
                    conn.send(buildStatus("zone_removed", "Zone supprimee : " + zoneName));
                }
            }

            case "get_status" -> conn.send(buildFullStatus());
        }
    }

    // ── Chat message ──────────────────────────────────────────────────────────

    private void handleChatMessage(JsonObject json) {
        String zoneName  = json.has("zone")     ? json.get("zone").getAsString()  : "global";
        String zoneLabel = json.has("label")    ? json.get("label").getAsString() : zoneName;
        String url       = json.has("url")      ? json.get("url").getAsString()   : null;
        String trackName = json.has("track")    ? json.get("track").getAsString() : null;
        boolean starting = !json.has("starting") || json.get("starting").getAsBoolean();

        Component msg;

        if (starting) {
            // Titre
            Component line1 = Component.text("♪ ")
                    .color(NamedTextColor.GOLD)
                    .append(Component.text(zoneLabel)
                            .color(NamedTextColor.YELLOW)
                            .decorate(TextDecoration.BOLD))
                    .append(Component.text(" est en cours !")
                            .color(NamedTextColor.GOLD));

            // Morceau
            Component line2 = (trackName != null && !trackName.isBlank())
                    ? Component.newline()
                        .append(Component.text("  » " + trackName)
                            .color(NamedTextColor.GRAY))
                    : Component.empty();

            // Lien cliquable
            Component line3 = Component.empty();
            if (url != null && !url.isBlank()) {
                line3 = Component.newline()
                        .append(Component.text("  "))
                        .append(Component.text("[Ecouter dans le navigateur]")
                                .color(NamedTextColor.AQUA)
                                .decorate(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(url))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text(url)
                                                .color(NamedTextColor.GRAY))));
            }

            msg = line1.append(line2).append(line3);

        } else {
            msg = Component.text("♪ ")
                    .color(NamedTextColor.GRAY)
                    .append(Component.text(zoneLabel).color(NamedTextColor.YELLOW))
                    .append(Component.text(" s'est arretee.").color(NamedTextColor.GRAY));
        }

        final Component finalMsg = msg;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.sendMessage(finalMsg);
            }
        });

        log.info("[Chat] Message broadcast pour zone : " + zoneName);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

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

    private String buildFullStatus() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "full_status");
        o.addProperty("clients", getConnections().size());
        JsonObject zonesJson = new JsonObject();
        for (Map.Entry<String, AudioZone> entry : zoneManager.getZones().entrySet()) {
            JsonObject zj = new JsonObject();
            zj.addProperty("streaming", entry.getValue().isStreaming());
            zj.addProperty("players",   entry.getValue().getPlayerCount());
            zonesJson.add(entry.getKey(), zj);
        }
        o.add("zones", zonesJson);
        o.addProperty("timestamp", System.currentTimeMillis());
        return GSON.toJson(o);
    }
}
