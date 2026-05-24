package com.soundonly.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.soundonly.SoundOnlyPlugin;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Filtre optionnel WorldGuard : seuls les joueurs dans les régions configurées
 * (ou ayant la permission bypass) entendent le son.
 *
 * Instancié uniquement si WorldGuard est présent.
 */
public class WorldGuardFilter {

    private final SoundOnlyPlugin plugin;

    public WorldGuardFilter(SoundOnlyPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Retourne true si le joueur est autorisé à entendre le son.
     */
    public boolean canHear(Player player) {
        if (!plugin.getConfig().getBoolean("worldguard.enabled", false)) {
            return true; // filtrage désactivé → tout le monde entend
        }

        // Permission bypass
        String bypassPerm = plugin.getConfig().getString(
                "worldguard.bypass-permission", "soundonly.worldguard.bypass");
        if (player.hasPermission(bypassPerm)) return true;

        // Vérification région
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionManager manager = container.get(BukkitAdapter.adapt(player.getWorld()));

            if (manager == null) {
                return plugin.getConfig().getBoolean("worldguard.allow-if-missing", true);
            }

            List<String> allowedRegions = plugin.getConfig().getStringList("worldguard.regions");

            // Récupère toutes les régions où se trouve le joueur
            com.sk89q.worldguard.protection.ApplicableRegionSet regions =
                    manager.getApplicableRegions(BukkitAdapter.asBlockVector(player.getLocation()));

            for (ProtectedRegion region : regions) {
                if (allowedRegions.contains(region.getId())) {
                    return true;
                }
            }

            if (plugin.getConfig().getBoolean("worldguard.debug", false)) {
                plugin.getLogger().info("[WG] " + player.getName() + " hors région – son bloqué.");
            }

            return false;

        } catch (Exception e) {
            plugin.getLogger().warning("Erreur WorldGuard canHear : " + e.getMessage());
            return plugin.getConfig().getBoolean("worldguard.allow-if-missing", true);
        }
    }
}
