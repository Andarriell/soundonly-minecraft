package com.soundonly.worldguard;

import com.soundonly.SoundOnlyPlugin;
import org.bukkit.entity.Player;

/**
 * Conservé pour compatibilité — la logique de filtrage est maintenant
 * gérée par ZoneManager via PlayerMoveEvent.
 */
public class WorldGuardFilter {
    private final SoundOnlyPlugin plugin;
    public WorldGuardFilter(SoundOnlyPlugin plugin) { this.plugin = plugin; }
    public boolean canHear(Player player) { return true; }
}
