package com.df15.endregen;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sesion 2026-11-25 (a peticion del usuario: "placeholder para timer de cuanto falta para el siguiente dragon").
 * %endregen_next% (2d 3h 15m), %endregen_next_seconds%, %endregen_next_date%, %endregen_status%, %endregen_alive%,
 * %endregen_health%, %endregen_top_N_name%, %endregen_top_N_damage%, %endregen_top_N_percent%,
 * %endregen_my_damage%, %endregen_my_percent%.
 */
public final class EndRegenExpansion extends PlaceholderExpansion {
    private final EndRegen plugin;

    public EndRegenExpansion(EndRegen plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "endregen"; }
    @Override public String getAuthor() { return "df15"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String none = plugin.getConfig().getString("placeholder.no-data", "-");
        String p = params.toLowerCase();
        switch (p) {
            case "next": return plugin.nextText();
            case "next_seconds": return String.valueOf(Math.max(0, plugin.msToNext() / 1000));
            case "next_date": return plugin.nextDateText();
            case "status": return plugin.statusText();
            case "alive": return String.valueOf(plugin.dragonAlive());
            case "health": return plugin.dragonHealthText(none);
            case "my_damage":
                if (player == null) return none;
                return EndRegen.num(plugin.damageOf(player.getUniqueId()));
            case "my_percent":
                if (player == null) return none;
                return EndRegen.num(plugin.percentOf(plugin.damageOf(player.getUniqueId())));
            default: break;
        }
        if (p.startsWith("top_")) {
            String[] sp = p.split("_");
            if (sp.length != 3) return null;
            int n;
            try { n = Integer.parseInt(sp[1]); } catch (NumberFormatException e) { return null; }
            List<Map.Entry<UUID, Double>> r = plugin.ranking();
            if (n < 1 || n > r.size()) return none;
            Map.Entry<UUID, Double> en = r.get(n - 1);
            return switch (sp[2]) {
                case "name" -> plugin.nameOf(en.getKey());
                case "damage" -> EndRegen.num(en.getValue());
                case "percent" -> EndRegen.num(plugin.percentOf(en.getValue()));
                default -> null;
            };
        }
        return null;
    }
}
