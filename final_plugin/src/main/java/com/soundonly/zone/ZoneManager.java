package com.soundonly.zone;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.soundonly.SoundOnlyPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Gère toutes les zones audio et le routage des joueurs.
 * - Crée/supprime les zones à la demande du panel
 * - Détecte les changements de zone via PlayerMoveEvent
 * - Route chaque joueur vers sa zone courante
 */
public class ZoneManager implements Listener {

    private final SoundOnlyPlugin plugin;
    private final Logger log;
    private VoicechatServerApi serverApi;

    // Zones actives : nom → AudioZone
    private final Map<String, AudioZone> zones = new ConcurrentHashMap<>();

    // Zone courante par joueur : UUID → nom de zone (null = hors zone)
    private final Map<UUID, String> playerZone = new ConcurrentHashMap<>();

    // Scheduler tick 20ms pour toutes les zones
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;

    // WorldGuard disponible ?
    private boolean worldGuardAvailable = false;

    public ZoneManager(SoundOnlyPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();

        if (plugin.getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            worldGuardAvailable = true;
            log.info("[ZoneManager] WorldGuard détecté – détection de zones activée.");
        } else {
            log.warning("[ZoneManager] WorldGuard absent – les joueurs recevront toutes les zones.");
        }
    }

    public void setServerApi(VoicechatServerApi api) {
        this.serverApi = api;
        startTickTask();
        log.info("[ZoneManager] ServerApi initialisé.");
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    private void startTickTask() {
        if (tickTask != null) tickTask.cancel(false);
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SoundOnly-Zones");
                t.setDaemon(true);
                return t;
            });
        }
        tickTask = scheduler.scheduleAtFixedRate(this::tickAllZones, 0, 20, TimeUnit.MILLISECONDS);
    }

    private void tickAllZones() {
        for (AudioZone zone : zones.values()) {
            zone.tick();
        }
    }

    // ── Gestion des zones ─────────────────────────────────────────────────────

    /**
     * Crée une zone si elle n'existe pas encore.
     */
    public AudioZone getOrCreateZone(String name) {
        return zones.computeIfAbsent(name, n -> {
            if (serverApi == null) {
                log.warning("[ZoneManager] ServerApi pas encore prêt pour créer la zone : " + n);
                return null;
            }
            AudioZone zone = new AudioZone(n, serverApi, log);
            log.info("[ZoneManager] Zone créée : " + n);
            // Ajoute les joueurs déjà dans cette région
            refreshPlayersForZone(zone);
            return zone;
        });
    }

    /**
     * Supprime une zone et retire tous ses joueurs.
     */
    public void removeZone(String name) {
        AudioZone zone = zones.remove(name);
        if (zone != null) {
            zone.close();
            // Retire les joueurs de cette zone
            playerZone.entrySet().removeIf(e -> name.equals(e.getValue()));
            log.info("[ZoneManager] Zone supprimée : " + name);
        }
    }

    public Map<String, AudioZone> getZones() { return Collections.unmodifiableMap(zones); }

    public AudioZone getZone(String name) { return zones.get(name); }

    public boolean hasZone(String name) { return zones.containsKey(name); }

    // ── Routage joueurs ───────────────────────────────────────────────────────

    /**
     * Appelé quand un joueur se connecte à Voice Chat.
     */
    public void onPlayerConnected(UUID uuid, VoicechatConnection conn) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null) return;

        String zoneName = detectZone(player);
        if (zoneName != null) {
            assignPlayerToZone(uuid, conn, zoneName, player);
        }
        log.info("[ZoneManager] Joueur connecté : " + player.getName() +
                " → zone : " + (zoneName != null ? zoneName : "aucune"));
    }

    /**
     * Appelé quand un joueur se déconnecte de Voice Chat.
     */
    public void onPlayerDisconnected(UUID uuid) {
        String oldZone = playerZone.remove(uuid);
        if (oldZone != null) {
            AudioZone zone = zones.get(oldZone);
            if (zone != null) zone.removePlayer(uuid);
        }
    }

    /**
     * Détecte la zone WorldGuard d'un joueur (priorité = ordre de déclaration des zones actives).
     * Retourne null si le joueur n'est dans aucune zone active.
     */
    public String detectZone(Player player) {
        if (!worldGuardAvailable || zones.isEmpty()) return null;

        try {
            RegionManager manager = WorldGuard.getInstance()
                    .getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(player.getWorld()));

            if (manager == null) return null;

            com.sk89q.worldguard.protection.ApplicableRegionSet regions =
                    manager.getApplicableRegions(BukkitAdapter.asBlockVector(player.getLocation()));

            // Cherche la première zone active qui correspond à une région WorldGuard
            String bestZone = null;
            int bestPriority = Integer.MIN_VALUE;

            for (ProtectedRegion region : regions) {
                String regionId = region.getId();
                if (zones.containsKey(regionId)) {
                    if (region.getPriority() > bestPriority) {
                        bestPriority = region.getPriority();
                        bestZone     = regionId;
                    }
                }
            }
            return bestZone;

        } catch (Exception e) {
            log.warning("[ZoneManager] Erreur detectZone : " + e.getMessage());
            return null;
        }
    }

    private void assignPlayerToZone(UUID uuid, VoicechatConnection conn, String zoneName, Player player) {
        // Retire de l'ancienne zone
        String oldZone = playerZone.get(uuid);
        if (oldZone != null && !oldZone.equals(zoneName)) {
            AudioZone old = zones.get(oldZone);
            if (old != null) old.removePlayer(uuid);
        }

        // Ajoute dans la nouvelle zone
        AudioZone zone = zones.get(zoneName);
        if (zone != null && conn != null) {
            zone.addPlayer(uuid, conn, player.getWorld());
            playerZone.put(uuid, zoneName);
        }
    }

    private void refreshPlayersForZone(AudioZone zone) {
        if (serverApi == null) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            String detectedZone = detectZone(player);
            if (zone.getName().equals(detectedZone)) {
                VoicechatConnection conn = serverApi.getConnectionOf(player.getUniqueId());
                if (conn != null) {
                    zone.addPlayer(player.getUniqueId(), conn, player.getWorld());
                    playerZone.put(player.getUniqueId(), zone.getName());
                }
            }
        }
    }

    // ── PlayerMoveEvent ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Optimisation : ignore si le joueur n'a pas changé de bloc
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockY() == event.getTo().getBlockY() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        if (!worldGuardAvailable || zones.isEmpty()) return;

        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        String newZone = detectZone(player);
        String oldZone = playerZone.get(uuid);

        // Pas de changement
        if (Objects.equals(newZone, oldZone)) return;

        if (serverApi == null) return;
        VoicechatConnection conn = serverApi.getConnectionOf(uuid);
        if (conn == null) return;

        // Change de zone
        if (oldZone != null) {
            AudioZone old = zones.get(oldZone);
            if (old != null) old.removePlayer(uuid);
        }

        if (newZone != null) {
            AudioZone zone = zones.get(newZone);
            if (zone != null) {
                zone.addPlayer(uuid, conn, player.getWorld());
                playerZone.put(uuid, newZone);
                log.info("[ZoneManager] " + player.getName() + " : " +
                        (oldZone != null ? oldZone : "hors-zone") + " → " + newZone);
            }
        } else {
            playerZone.remove(uuid);
            log.info("[ZoneManager] " + player.getName() + " a quitté la zone " + oldZone);
        }
    }

    // ── Statut ────────────────────────────────────────────────────────────────

    public String getPlayerZone(UUID uuid) { return playerZone.get(uuid); }

    public Map<UUID, String> getAllPlayerZones() { return Collections.unmodifiableMap(playerZone); }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    public void shutdown() {
        if (tickTask != null)  { tickTask.cancel(false);   tickTask  = null; }
        if (scheduler != null) { scheduler.shutdownNow();  scheduler = null; }
        zones.values().forEach(AudioZone::close);
        zones.clear();
        playerZone.clear();
        log.info("[ZoneManager] Arrêté.");
    }
}
