package com.soundonly.voice;

import com.soundonly.SoundOnlyPlugin;
import com.soundonly.zone.AudioZone;
import com.soundonly.zone.ZoneManager;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.logging.Logger;

public class VoicechatIntegration implements VoicechatPlugin, Listener {

    private final SoundOnlyPlugin plugin;
    private final Logger log;
    private final ZoneManager zoneManager;

    private VoicechatServerApi serverApi;

    public VoicechatIntegration(SoundOnlyPlugin plugin, ZoneManager zoneManager) {
        this.plugin      = plugin;
        this.log         = plugin.getLogger();
        this.zoneManager = zoneManager;

        BukkitVoicechatService service = plugin.getServer()
                .getServicesManager()
                .load(BukkitVoicechatService.class);

        if (service != null) {
            service.registerPlugin(this);
            log.info("Simple Voice Chat API enregistrée.");
        } else {
            log.warning("Simple Voice Chat introuvable.");
        }
    }

    @Override
    public String getPluginId() { return "soundonly"; }

    @Override
    public void initialize(VoicechatApi api) {}

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(PlayerConnectedEvent.class,        this::onPlayerConnected);
        registration.registerEvent(PlayerDisconnectedEvent.class,     this::onPlayerDisconnected);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();
        zoneManager.setServerApi(serverApi);
        log.info("VoicechatServerApi prête.");

        // Initialise les connexions des joueurs déjà en ligne
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            var conn = serverApi.getConnectionOf(player.getUniqueId());
            if (conn != null) zoneManager.onPlayerConnected(player.getUniqueId(), conn);
        }
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        var conn = event.getConnection();
        if (conn == null) return;
        zoneManager.onPlayerConnected(conn.getPlayer().getUuid(), conn);
        log.info("[VC] Joueur connecté : " + conn.getPlayer().getUuid());
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        var uuid = event.getPlayerUuid();
        if (uuid == null) return;
        zoneManager.onPlayerDisconnected(uuid);
        log.info("[VC] Joueur déconnecté : " + uuid);
    }

    // ── Délégation vers ZoneManager ───────────────────────────────────────────

    /**
     * Diffuse un frame PCM vers une zone spécifique.
     */
    public void broadcastPcmToZone(String zoneName, byte[] pcmFrame) {
        AudioZone zone = zoneManager.getZone(zoneName);
        if (zone == null) {
            // Crée la zone à la volée si elle n'existe pas encore
            zone = zoneManager.getOrCreateZone(zoneName);
        }
        if (zone != null) zone.pushPcm(pcmFrame);
    }

    /**
     * Diffuse un frame Opus vers une zone spécifique.
     */
    public void broadcastOpusToZone(String zoneName, byte[] opusFrame) {
        AudioZone zone = zoneManager.getZone(zoneName);
        if (zone == null) zone = zoneManager.getOrCreateZone(zoneName);
        if (zone != null) zone.pushOpus(opusFrame);
    }

    /**
     * Arrête le streaming d'une zone.
     */
    public void stopZone(String zoneName) {
        AudioZone zone = zoneManager.getZone(zoneName);
        if (zone != null) zone.stopStreaming();
    }

    /**
     * Arrête toutes les zones.
     */
    public void stopAllZones() {
        zoneManager.getZones().values().forEach(AudioZone::stopStreaming);
    }

    /**
     * Compatibilité avec l'ancien système (zone "global").
     */
    public void broadcastPcm(byte[] pcmFrame, com.soundonly.worldguard.WorldGuardFilter filter) {
        broadcastPcmToZone("global", pcmFrame);
    }

    public void broadcastOpus(byte[] opusFrame, com.soundonly.worldguard.WorldGuardFilter filter) {
        broadcastOpusToZone("global", opusFrame);
    }

    public void stopStreaming() { stopAllZones(); }

    public boolean isStreaming() {
        return zoneManager.getZones().values().stream().anyMatch(AudioZone::isStreaming);
    }

    public boolean isZoneStreaming(String zoneName) {
        AudioZone zone = zoneManager.getZone(zoneName);
        return zone != null && zone.isStreaming();
    }

    public void shutdown() {
        zoneManager.shutdown();
    }
}
