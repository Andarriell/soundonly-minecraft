package com.soundonly.zone;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import org.bukkit.World;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * Représente une zone audio indépendante.
 * Chaque zone a son propre encodeur Opus, sa propre file de frames
 * et ses propres canaux StaticAudioChannel par joueur.
 */
public class AudioZone {

    private final String name;
    private final VoicechatServerApi serverApi;
    private final Logger log;

    // Encodeur Opus dédié à cette zone
    private OpusEncoder encoder;

    // File de frames Opus prêtes à envoyer
    private final ConcurrentLinkedQueue<byte[]> opusQueue = new ConcurrentLinkedQueue<>();

    // Canal audio par joueur (UUID → canal)
    private final Map<UUID, StaticAudioChannel> playerChannels = new ConcurrentHashMap<>();

    private boolean streaming    = false;
    private boolean muted        = false;
    private String  currentTrack = null;

    public AudioZone(String name, VoicechatServerApi serverApi, Logger log) {
        this.name      = name;
        this.serverApi = serverApi;
        this.log       = log;
        this.encoder   = serverApi.createEncoder(OpusEncoderMode.AUDIO);
        log.info("[Zone:" + name + "] Créée avec encodeur AUDIO.");
    }

    // ── Canaux joueurs ────────────────────────────────────────────────────────

    public void addPlayer(UUID uuid, VoicechatConnection conn, World world) {
        if (playerChannels.containsKey(uuid)) return;
        try {
            StaticAudioChannel channel = serverApi.createStaticAudioChannel(
                    UUID.randomUUID(),
                    serverApi.fromServerLevel(world),
                    conn
            );
            if (channel == null) {
                log.warning("[Zone:" + name + "] Canal null pour " + uuid);
                return;
            }
            playerChannels.put(uuid, channel);
            log.info("[Zone:" + name + "] Joueur ajouté : " + uuid);
        } catch (Exception e) {
            log.warning("[Zone:" + name + "] Erreur addPlayer : " + e.getMessage());
        }
    }

    public void removePlayer(UUID uuid) {
        playerChannels.remove(uuid);
        log.info("[Zone:" + name + "] Joueur retiré : " + uuid);
    }

    public boolean hasPlayer(UUID uuid) {
        return playerChannels.containsKey(uuid);
    }

    public int getPlayerCount() {
        return playerChannels.size();
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    /**
     * Reçoit un frame PCM, l'encode en Opus et le pousse dans la file.
     */
    public void pushPcm(byte[] pcmFrame) {
        if (encoder == null) return;
        streaming = true;

        short[] samples = new short[pcmFrame.length / 2];
        ByteBuffer.wrap(pcmFrame)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(samples);

        try {
            byte[] opus = encoder.encode(samples);
            if (opus != null) {
                opusQueue.offer(opus);
                while (opusQueue.size() > 200) opusQueue.poll();
            }
        } catch (Exception e) {
            log.warning("[Zone:" + name + "] Erreur encodage : " + e.getMessage());
            try { encoder.resetState(); } catch (Exception ignored) {}
        }
    }

    /**
     * Reçoit un frame Opus déjà encodé.
     */
    public void pushOpus(byte[] opusFrame) {
        streaming = true;
        opusQueue.offer(opusFrame);
        while (opusQueue.size() > 200) opusQueue.poll();
    }

    /**
     * Appelé par le tick scheduler — envoie 1 frame aux joueurs de cette zone.
     */
    public void tick() {
        if (playerChannels.isEmpty() || opusQueue.isEmpty()) return;

        byte[] frame = opusQueue.poll();
        if (frame == null) return;

        for (Map.Entry<UUID, StaticAudioChannel> entry : playerChannels.entrySet()) {
            try {
                entry.getValue().send(frame);
            } catch (Exception e) {
                log.warning("[Zone:" + name + "] Erreur envoi frame à " + entry.getKey() + " : " + e.getMessage());
            }
        }
    }

    public void stopStreaming() {
        streaming = false;
        opusQueue.clear();
        resetEncoder();
    }

    private void resetEncoder() {
        try {
            if (encoder != null) { encoder.close(); encoder = null; }
            encoder = serverApi.createEncoder(OpusEncoderMode.AUDIO);
        } catch (Exception e) {
            log.warning("[Zone:" + name + "] Erreur reset encodeur : " + e.getMessage());
        }
    }

    public void close() {
        opusQueue.clear();
        playerChannels.clear();
        try { if (encoder != null) encoder.close(); } catch (Exception ignored) {}
        log.info("[Zone:" + name + "] Fermée.");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public boolean isMuted()          { return muted; }
    public void setMuted(boolean m)   { this.muted = m; }
    public String getCurrentTrack()   { return currentTrack; }
    public void setCurrentTrack(String t) { this.currentTrack = t; }

    public String getName()       { return name; }
    public boolean isStreaming()  { return streaming; }
    public ConcurrentLinkedQueue<byte[]> getOpusQueue() { return opusQueue; }
}
