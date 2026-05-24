package com.soundonly.voice;

import com.soundonly.SoundOnlyPlugin;
import com.soundonly.worldguard.WorldGuardFilter;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class VoicechatIntegration implements VoicechatPlugin, Listener {

    private final SoundOnlyPlugin plugin;
    private final Logger log;

    private VoicechatServerApi serverApi;
    private OpusEncoder pcmEncoder;

    // Canal statique par joueur
    private final Map<UUID, AudioChannel> playerChannels = new ConcurrentHashMap<>();

    // File d'opus frames encodées — partagée, broadcast vers tous
    private final ConcurrentLinkedQueue<byte[]> opusQueue = new ConcurrentLinkedQueue<>();

    private final AtomicBoolean streaming = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;

    public VoicechatIntegration(SoundOnlyPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();

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
        serverApi  = event.getVoicechat();
        pcmEncoder = serverApi.createEncoder(OpusEncoderMode.AUDIO);

        if (pcmEncoder == null) {
            log.warning("Impossible de créer l'encodeur PCM.");
            return;
        }

        log.info("VoicechatServerApi prête – encodeur AUDIO créé.");
        startTickTask();

        // Ouvre les canaux pour les joueurs déjà en ligne
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            VoicechatConnection conn = serverApi.getConnectionOf(player.getUniqueId());
            if (conn != null) createChannel(conn);
        }
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        VoicechatConnection conn = event.getConnection();
        if (conn == null) return;
        Player player = plugin.getServer().getPlayer(conn.getPlayer().getUuid());
        if (player != null) {
            log.info("[VoiceChat] Player connected: " + player.getName());
        }
        createChannel(conn);
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        UUID uuid = event.getPlayerUuid();
        if (uuid == null) return;
        playerChannels.remove(uuid);
        log.info("[VoiceChat] Player disconnected: " + uuid);
    }

    private void createChannel(VoicechatConnection conn) {
        UUID uuid = conn.getPlayer().getUuid();
        if (playerChannels.containsKey(uuid)) return;

        try {
            StaticAudioChannel channel = serverApi.createStaticAudioChannel(
                    UUID.randomUUID(),
                    serverApi.fromServerLevel(
                            plugin.getServer().getPlayer(uuid) != null
                                    ? plugin.getServer().getPlayer(uuid).getWorld()
                                    : plugin.getServer().getWorlds().get(0)
                    ),
                    conn
            );

            if (channel == null) {
                log.warning("[VoiceChat] Failed to create static channel for " + uuid);
                return;
            }

            playerChannels.put(uuid, channel);
            log.info("[VoiceChat] Created static audio channel for " + uuid);

        } catch (Exception e) {
            log.warning("[VoiceChat] Error creating channel: " + e.getMessage());
        }
    }

    // ── Tick task — envoie les frames Opus aux joueurs ───────────────────────

    private void startTickTask() {
        if (tickTask != null) tickTask.cancel(false);
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SoundOnly-Audio");
                t.setDaemon(true);
                return t;
            });
        }
        // 20ms = 1 frame Voice Chat (960 samples @ 48kHz)
        tickTask = scheduler.scheduleAtFixedRate(this::tickSendAudio, 0, 20, TimeUnit.MILLISECONDS);
    }

    private void tickSendAudio() {
        if (playerChannels.isEmpty() || opusQueue.isEmpty()) return;

        // 1 frame par 20ms — cadence exacte Voice Chat
        byte[] opusFrame = opusQueue.poll();
        if (opusFrame == null) return;

        for (Map.Entry<UUID, AudioChannel> entry : playerChannels.entrySet()) {
            try {
                AudioChannel channel = entry.getValue();
                if (channel instanceof StaticAudioChannel sc) {
                    sc.send(opusFrame);
                }
            } catch (Exception e) {
                log.warning("[VoiceChat] Failed to send audio frame: " + e.getMessage());
            }
        }
    }

    // ── Diffusion PCM → encode → queue ───────────────────────────────────────

    public void broadcastPcm(byte[] pcmFrame, WorldGuardFilter filter) {
        if (serverApi == null || pcmEncoder == null) return;
        streaming.set(true);

        short[] samples = new short[pcmFrame.length / 2];
        ByteBuffer.wrap(pcmFrame)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(samples);

        try {
            byte[] opus = pcmEncoder.encode(samples);
            if (opus != null) {
                opusQueue.offer(opus);
                // Limite la taille de la queue
                while (opusQueue.size() > 200) opusQueue.poll();
            }
        } catch (Exception e) {
            log.warning("[VoiceChat] PCM encode failed: " + e.getMessage());
            try { pcmEncoder.resetState(); } catch (Exception ignored) {}
        }
    }

    public void broadcastOpus(byte[] opusFrame, WorldGuardFilter filter) {
        if (serverApi == null) return;
        streaming.set(true);
        opusQueue.offer(opusFrame);
        while (opusQueue.size() > 200) opusQueue.poll();
    }

    public void stopStreaming() {
        streaming.set(false);
        opusQueue.clear();
        try { if (pcmEncoder != null) pcmEncoder.resetState(); } catch (Exception ignored) {}
    }

    public boolean isStreaming() { return streaming.get(); }

    public void configure(String channelType, int distance, String zone) {
        log.info("[VoiceChat] Config updated: type=" + channelType + ", distance=" + distance + ", zone=" + zone);
    }

    public void shutdown() {
        if (tickTask != null) { tickTask.cancel(false); tickTask = null; }
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        opusQueue.clear();
        playerChannels.clear();
        try { if (pcmEncoder != null) pcmEncoder.close(); } catch (Exception ignored) {}
    }
}
