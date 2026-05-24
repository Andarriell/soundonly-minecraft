package com.soundonly;

import com.soundonly.voice.VoicechatIntegration;
import com.soundonly.websocket.AudioWebSocketServer;
import com.soundonly.worldguard.WorldGuardFilter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;

public class SoundOnlyPlugin extends JavaPlugin {

    private static SoundOnlyPlugin instance;
    private AudioWebSocketServer wsServer;
    private VoicechatIntegration voicechat;
    private WorldGuardFilter worldGuardFilter;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();

        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            worldGuardFilter = new WorldGuardFilter(this);
            getLogger().info("WorldGuard détecté.");
        }

        voicechat = new VoicechatIntegration(this);
        getServer().getPluginManager().registerEvents(voicechat, this);

        String address = getConfig().getString("websocket.address", "0.0.0.0");
        int    port    = getConfig().getInt("websocket.port", 8765);

        wsServer = new AudioWebSocketServer(
                new InetSocketAddress(address, port),
                this, voicechat, worldGuardFilter
        );

        wsServer.setReuseAddr(true);
        wsServer.start();
        getLogger().info("WebSocket démarré sur " + address + ":" + port);
    }

    @Override
    public void onDisable() {
        voicechat.shutdown();
        if (wsServer != null) {
            try { wsServer.stop(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("soundonly")) return false;
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "status" -> {
                sender.sendMessage("[SoundOnly] WS actif: " + (wsServer != null));
                sender.sendMessage("[SoundOnly] Streaming: " + voicechat.isStreaming());
                sender.sendMessage("[SoundOnly] Clients connectés: " + (wsServer != null ? wsServer.getConnections().size() : 0));
            }
            case "stop"   -> { voicechat.stopStreaming(); sender.sendMessage("[SoundOnly] Stream arrêté."); }
            case "reload" -> { reloadConfig(); sender.sendMessage("[SoundOnly] Config rechargée."); }
            default -> sender.sendMessage("[SoundOnly] Usage: /soundonly <status|stop|reload>");
        }
        return true;
    }

    public static SoundOnlyPlugin getInstance() { return instance; }
    public WorldGuardFilter getWorldGuardFilter() { return worldGuardFilter; }
}
