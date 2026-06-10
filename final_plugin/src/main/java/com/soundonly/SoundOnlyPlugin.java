package com.soundonly;

import com.soundonly.voice.VoicechatIntegration;
import com.soundonly.websocket.AudioWebSocketServer;
import com.soundonly.zone.AudioZone;
import com.soundonly.zone.ZoneManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SoundOnlyPlugin extends JavaPlugin implements Listener {

    private static SoundOnlyPlugin instance;
    private AudioWebSocketServer wsServer;
    private VoicechatIntegration voicechat;
    private ZoneManager zoneManager;

    private String welcomeUrl = null;
    private String panelUrl   = null;

    // Zone sélectionnée par joueur pour /so mute|unmute sans argument
    private final Map<UUID, String> playerSelectedZone = new HashMap<>();

    // Labels lisibles des zones
    private static final Map<String, String> ZONE_LABELS = new HashMap<>();
    static {
        ZONE_LABELS.put("main_stage", "Main Stage");
        ZONE_LABELS.put("esprit",     "Esprit");
        ZONE_LABELS.put("vegetal",    "Vegetal");
        ZONE_LABELS.put("futuriste",  "Futuriste");
    }
    private String zoneLabel(String name) {
        return ZONE_LABELS.getOrDefault(name, name);
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();

        zoneManager = new ZoneManager(this);
        getServer().getPluginManager().registerEvents(zoneManager, this);
        getServer().getPluginManager().registerEvents(this, this);

        voicechat = new VoicechatIntegration(this, zoneManager);
        getServer().getPluginManager().registerEvents(voicechat, this);

        String address = getConfig().getString("websocket.address", "0.0.0.0");
        int    port    = getConfig().getInt("websocket.port", 8765);

        wsServer = new AudioWebSocketServer(
                new InetSocketAddress(address, port),
                this, voicechat, zoneManager
        );
        wsServer.setReuseAddr(true);
        wsServer.start();
        getLogger().info("WebSocket demarre sur " + address + ":" + port);

        // Charge les URLs depuis config.yml comme valeur par defaut
        String cfgListener = getConfig().getString("panel.listener-url", "");
        String cfgAdmin    = getConfig().getString("panel.admin-url", "");
        if (!cfgListener.isBlank() && !cfgListener.contains("TON-IP")) {
            welcomeUrl = cfgListener;
            panelUrl   = cfgAdmin.isBlank() ? cfgListener.replace("/listener.html", "") : cfgAdmin;
            getLogger().info("URLs panel chargees depuis config.yml");
        }
    }

    @Override
    public void onDisable() {
        voicechat.shutdown();
        if (wsServer != null) {
            try { wsServer.stop(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ── PlayerJoin : message de bienvenue ────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (welcomeUrl == null || welcomeUrl.isBlank()) return;
        final String url = welcomeUrl;
        getServer().getScheduler().runTaskLater(this, () -> {
            Component msg = Component.text("♪ ")
                    .color(NamedTextColor.GOLD)
                    .append(Component.text("Son live disponible sur ce serveur ! ")
                            .color(NamedTextColor.YELLOW))
                    .append(Component.newline())
                    .append(Component.text("  "))
                    .append(Component.text("[Ecouter les scenes en direct]")
                            .color(NamedTextColor.AQUA)
                            .decorate(TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("Cliquer pour ouvrir dans le navigateur")
                                            .color(NamedTextColor.GRAY))));
            event.getPlayer().sendMessage(msg);
        }, 40L);
    }

    // ── Commandes ─────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();
        if (!name.equals("soundonly") && !name.equals("so")) return false;

        String sub = args.length > 0 ? args[0].toLowerCase() : "help";

        switch (sub) {

            // ── /so zone ─────────────────────────────────────────────────────
            case "zone" -> {
                if (!sender.hasPermission("soundonly.zone")) {
                    sender.sendMessage(Component.text("Permission refusee.").color(NamedTextColor.RED));
                    return true;
                }
                Map<String, AudioZone> zones = zoneManager.getZones();
                if (zones.isEmpty()) {
                    sender.sendMessage(Component.text("Aucune zone active.").color(NamedTextColor.GRAY));
                    return true;
                }
                sender.sendMessage(Component.text("--- Zones actives ---").color(NamedTextColor.GOLD));
                for (Map.Entry<String, AudioZone> entry : zones.entrySet()) {
                    AudioZone z = entry.getValue();
                    String status = z.isStreaming()
                            ? (z.isMuted() ? "[MUTE]" : "[LIVE]")
                            : "[OFF]";
                    NamedTextColor color = z.isStreaming()
                            ? (z.isMuted() ? NamedTextColor.GRAY : NamedTextColor.GREEN)
                            : NamedTextColor.DARK_GRAY;
                    sender.sendMessage(Component.text(" " + status + " ")
                            .color(color)
                            .append(Component.text(entry.getKey()).color(NamedTextColor.YELLOW))
                            .append(Component.text(" | " + z.getPlayerCount() + " joueur(s)")
                                    .color(NamedTextColor.GRAY)));
                }
            }

            // ── /so label ────────────────────────────────────────────────────
            case "label" -> {
                if (!sender.hasPermission("soundonly.label")) {
                    sender.sendMessage(Component.text("Permission refusee.").color(NamedTextColor.RED));
                    return true;
                }
                Map<String, AudioZone> zones = zoneManager.getZones();
                sender.sendMessage(Component.text("--- Scenes en cours ---").color(NamedTextColor.GOLD));
                for (Map.Entry<String, AudioZone> entry : zones.entrySet()) {
                    AudioZone z = entry.getValue();
                    boolean live = z.isStreaming() && !z.isMuted();
                    String track = z.getCurrentTrack();

                    // Formate le nom du fichier : "Artiste - Titre.mp3" -> "Artiste - Titre"
                    String trackDisplay = "";
                    if (track != null && !track.isBlank()) {
                        int dot = track.lastIndexOf('.');
                        trackDisplay = dot > 0 ? track.substring(0, dot) : track;
                    }

                    Component line = Component.text("  ")
                            .append(Component.text(live ? "▶ " : "■ ")
                                    .color(live ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY))
                            .append(Component.text(zoneLabel(entry.getKey()))
                                    .color(live ? NamedTextColor.AQUA : NamedTextColor.GRAY)
                                    .decorate(TextDecoration.BOLD));

                    if (live && !trackDisplay.isEmpty()) {
                        line = line.append(Component.text(" — " + trackDisplay)
                                .color(NamedTextColor.WHITE));
                    } else if (z.isMuted()) {
                        line = line.append(Component.text(" [MUTE]")
                                .color(NamedTextColor.RED));
                    } else if (!z.isStreaming()) {
                        line = line.append(Component.text(" — Aucun son")
                                .color(NamedTextColor.DARK_GRAY));
                    }

                    sender.sendMessage(line);
                }
            }

            // ── /so link ─────────────────────────────────────────────────────
            case "link" -> {
                if (!sender.hasPermission("soundonly.link")) {
                    sender.sendMessage(Component.text("Permission refusee.").color(NamedTextColor.RED));
                    return true;
                }
                if (welcomeUrl == null || welcomeUrl.isBlank()) {
                    sender.sendMessage(Component.text("Lien non configure (PANEL_URL manquant).").color(NamedTextColor.RED));
                    return true;
                }
                Component link = Component.text("♪ Ecouter en direct : ")
                        .color(NamedTextColor.GOLD)
                        .append(Component.text("[Ouvrir]")
                                .color(NamedTextColor.AQUA)
                                .decorate(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(welcomeUrl))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text(welcomeUrl).color(NamedTextColor.GRAY))));
                sender.sendMessage(link);
            }

            // ── /so select [zone] ────────────────────────────────────────────
            case "select" -> {
                if (!sender.hasPermission("soundonly.select")) {
                    sender.sendMessage(Component.text("Permission refusee.").color(NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    // Affiche les zones disponibles
                    sender.sendMessage(Component.text("Zones disponibles :").color(NamedTextColor.GOLD));
                    for (String zoneName : zoneManager.getZones().keySet()) {
                        sender.sendMessage(Component.text("  /so select " + zoneName)
                                .color(NamedTextColor.YELLOW)
                                .clickEvent(ClickEvent.runCommand("/so select " + zoneName))
                                .hoverEvent(HoverEvent.showText(Component.text("Cliquer pour selectionner"))));
                    }
                    return true;
                }
                String zoneName = args[1].toLowerCase();
                if (!zoneManager.hasZone(zoneName)) {
                    sender.sendMessage(Component.text("Zone introuvable : " + zoneName).color(NamedTextColor.RED));
                    return true;
                }
                if (sender instanceof Player player) {
                    playerSelectedZone.put(player.getUniqueId(), zoneName);
                }
                sender.sendMessage(Component.text("Zone selectionnee : ")
                        .color(NamedTextColor.GREEN)
                        .append(Component.text(zoneLabel(zoneName)).color(NamedTextColor.YELLOW)));
            }

            // ── /so mute [zone] ──────────────────────────────────────────────
            case "mute" -> {
                if (!sender.hasPermission("soundonly.mute")) {
                    sender.sendMessage(Component.text("Permission refusee.").color(NamedTextColor.RED));
                    return true;
                }
                String zoneName = resolveZone(sender, args, 1);
                if (zoneName == null) {
                    sender.sendMessage(Component.text("Specifie une zone ou selectionne-en une avec /so select.").color(NamedTextColor.RED));
                    return true;
                }
                AudioZone zone = zoneManager.getZone(zoneName);
                if (zone == null) {
                    sender.sendMessage(Component.text("Zone introuvable : " + zoneName).color(NamedTextColor.RED));
                    return true;
                }
                zone.setMuted(true);
                sender.sendMessage(Component.text("Zone ")
                        .color(NamedTextColor.GRAY)
                        .append(Component.text(zoneLabel(zoneName)).color(NamedTextColor.YELLOW))
                        .append(Component.text(" mutee.").color(NamedTextColor.GRAY)));
                getLogger().info("[SoundOnly] Zone " + zoneName + " mutee par " + sender.getName());
            }

            // ── /so unmute [zone] ────────────────────────────────────────────
            case "unmute" -> {
                if (!sender.hasPermission("soundonly.unmute")) {
                    sender.sendMessage(Component.text("Permission refusee.").color(NamedTextColor.RED));
                    return true;
                }
                String zoneName = resolveZone(sender, args, 1);
                if (zoneName == null) {
                    sender.sendMessage(Component.text("Specifie une zone ou selectionne-en une avec /so select.").color(NamedTextColor.RED));
                    return true;
                }
                AudioZone zone = zoneManager.getZone(zoneName);
                if (zone == null) {
                    sender.sendMessage(Component.text("Zone introuvable : " + zoneName).color(NamedTextColor.RED));
                    return true;
                }
                zone.setMuted(false);
                sender.sendMessage(Component.text("Zone ")
                        .color(NamedTextColor.GREEN)
                        .append(Component.text(zoneLabel(zoneName)).color(NamedTextColor.YELLOW))
                        .append(Component.text(" demutee.").color(NamedTextColor.GREEN)));
                getLogger().info("[SoundOnly] Zone " + zoneName + " demutee par " + sender.getName());
            }

            // ── /so panel ────────────────────────────────────────────────────
            case "panel" -> {
                if (!sender.hasPermission("soundonly.panel")) {
                    sender.sendMessage(Component.text("Permission refusee.").color(NamedTextColor.RED));
                    return true;
                }
                if (panelUrl == null || panelUrl.isBlank()) {
                    sender.sendMessage(Component.text("URL du panel non configuree.").color(NamedTextColor.RED));
                    return true;
                }
                Component link = Component.text("Panel admin : ")
                        .color(NamedTextColor.GOLD)
                        .append(Component.text("[Ouvrir le panel]")
                                .color(NamedTextColor.GREEN)
                                .decorate(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(panelUrl))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text(panelUrl).color(NamedTextColor.GRAY))));
                sender.sendMessage(link);
            }

            // ── /so status (admin) ────────────────────────────────────────────
            case "status" -> {
                if (!sender.hasPermission("soundonly.control")) {
                    sender.sendMessage(Component.text("Permission refusee.").color(NamedTextColor.RED));
                    return true;
                }
                sender.sendMessage("§6[SoundOnly] §fWS actif: " + (wsServer != null));
                sender.sendMessage("§6[SoundOnly] §fClients WS: " + (wsServer != null ? wsServer.getConnections().size() : 0));
                sender.sendMessage("§6[SoundOnly] §fWelcome URL: " + (welcomeUrl != null ? welcomeUrl : "non configuree"));
                Map<String, AudioZone> zones = zoneManager.getZones();
                for (Map.Entry<String, AudioZone> entry : zones.entrySet()) {
                    AudioZone z = entry.getValue();
                    sender.sendMessage("  §e" + entry.getKey() +
                            " §f→ streaming: " + z.isStreaming() +
                            " | muted: " + z.isMuted() +
                            " | joueurs: " + z.getPlayerCount());
                }
            }

            // ── /so stop [zone] (admin) ──────────────────────────────────────
            case "stop" -> {
                if (!sender.hasPermission("soundonly.control")) {
                    sender.sendMessage(Component.text("Permission refusee.").color(NamedTextColor.RED));
                    return true;
                }
                if (args.length > 1) {
                    voicechat.stopZone(args[1]);
                    sender.sendMessage("§6[SoundOnly] §fZone " + args[1] + " arretee.");
                } else {
                    voicechat.stopAllZones();
                    sender.sendMessage("§6[SoundOnly] §fToutes les zones arretees.");
                }
            }

            // ── /so reload (admin) ───────────────────────────────────────────
            case "reload" -> {
                if (!sender.hasPermission("soundonly.control")) {
                    sender.sendMessage(Component.text("Permission refusee.").color(NamedTextColor.RED));
                    return true;
                }
                reloadConfig();
                sender.sendMessage("§6[SoundOnly] §fConfig rechargee.");
            }

            // ── /so help ─────────────────────────────────────────────────────
            default -> {
                sender.sendMessage(Component.text("=== SoundOnly ===").color(NamedTextColor.GOLD));
                if (sender.hasPermission("soundonly.zone"))
                    sender.sendMessage(Component.text("  /so zone").color(NamedTextColor.YELLOW)
                            .append(Component.text(" - Zones actives et leur son").color(NamedTextColor.GRAY)));
                if (sender.hasPermission("soundonly.label"))
                    sender.sendMessage(Component.text("  /so label").color(NamedTextColor.YELLOW)
                            .append(Component.text(" - Noms et statut des scenes").color(NamedTextColor.GRAY)));
                if (sender.hasPermission("soundonly.link"))
                    sender.sendMessage(Component.text("  /so link").color(NamedTextColor.YELLOW)
                            .append(Component.text(" - Lien d'ecoute").color(NamedTextColor.GRAY)));
                if (sender.hasPermission("soundonly.select"))
                    sender.sendMessage(Component.text("  /so select <zone>").color(NamedTextColor.YELLOW)
                            .append(Component.text(" - Selectionner une zone").color(NamedTextColor.GRAY)));
                if (sender.hasPermission("soundonly.mute"))
                    sender.sendMessage(Component.text("  /so mute [zone]").color(NamedTextColor.AQUA)
                            .append(Component.text(" - Muter une zone").color(NamedTextColor.GRAY)));
                if (sender.hasPermission("soundonly.unmute"))
                    sender.sendMessage(Component.text("  /so unmute [zone]").color(NamedTextColor.AQUA)
                            .append(Component.text(" - Demuter une zone").color(NamedTextColor.GRAY)));
                if (sender.hasPermission("soundonly.panel"))
                    sender.sendMessage(Component.text("  /so panel").color(NamedTextColor.AQUA)
                            .append(Component.text(" - Lien vers le panel admin").color(NamedTextColor.GRAY)));
            }
        }
        return true;
    }

    // Résout la zone : argument ou zone sélectionnée par le joueur
    private String resolveZone(CommandSender sender, String[] args, int index) {
        if (args.length > index) return args[index].toLowerCase();
        if (sender instanceof Player player) {
            return playerSelectedZone.get(player.getUniqueId());
        }
        return null;
    }

    public static SoundOnlyPlugin getInstance() { return instance; }
    public ZoneManager getZoneManager()         { return zoneManager; }

    public void setWelcomeUrl(String url) {
        // La config WebSocket a priorite sur config.yml
        if (url != null && !url.isBlank()) {
            this.welcomeUrl = url;
            this.panelUrl   = url.replace("/listener.html", "");
            getLogger().info("URLs panel mises a jour via WebSocket : " + url);
        }
    }
    public String getWelcomeUrl() { return welcomeUrl; }
}
