package com.df15.endregen;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Sesion 2026-11-25 (a peticion del usuario: directrices del plugin del End; v1.2: SIN reiniciar el servidor).
 *
 * - Horario (dia/hora) en config. A la hora: aviso previo y spawn del dragon EN CALIENTE en el End (sin reinicio).
 * - La isla se protege con regiones WorldGuard (no la toca el plugin); opcionalmente se regeneran los cristales.
 * - Solo el plugin puede hacer aparecer al dragon (flag spawnAllowed); cualquier otro spawn se cancela.
 * - Vida y dano configurables. Top dano. Drops con chance, repartidos entre el top por % de dano.
 * - Bossbar propia (con vida 575/1000) para quien esta cerca del dragon. Sin huevo-bloque. Placeholders PAPI.
 */
public final class EndRegen extends JavaPlugin implements Listener, TabExecutor {
    private static final long MIN = 60_000L;
    private static final LegacyComponentSerializer LEG =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private File stateFile, damageFile, pendingFile;
    private YamlConfiguration state;
    private NamespacedKey tagKey, noUseKey;
    private boolean damageDirty;
    private int tickCount;
    private UUID dragonUuid;
    private BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    private final Set<Integer> warned = new HashSet<>();
    private final Map<UUID, Double> damage = new LinkedHashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Random rnd = new Random();

    // ------------------------------------------------------------------ arranque
    @Override
    public void onLoad() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        stateFile = new File(getDataFolder(), "state.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);
        if (!state.contains("lastSpawn")) {
            state.set("lastSpawn", state.contains("lastReset") ? state.getLong("lastReset") : System.currentTimeMillis());
            saveState();
        }
    }

    @Override
    public void onEnable() {
        tagKey = new NamespacedKey(this, "dragon");
        noUseKey = new NamespacedKey(this, "no_use");
        damageFile = new File(getDataFolder(), "damage.yml");
        pendingFile = new File(getDataFolder(), "pending.yml");
        String u = state.getString("dragonUuid");
        if (u != null) { try { dragonUuid = UUID.fromString(u); } catch (IllegalArgumentException ignored) { } }
        loadDamage();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("endregen");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new EndRegenExpansion(this).register();
            getLogger().info("Placeholders %endregen_*% registrados.");
        }
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 10L);
    }

    @Override
    public void onDisable() {
        hideBar();
        if (damageFile != null) saveDamage();
    }

    // ------------------------------------------------------------------ horario
    private ZoneId zone() {
        try { return ZoneId.of(getConfig().getString("schedule.timezone", "Europe/Madrid")); }
        catch (Exception e) { return ZoneId.systemDefault(); }
    }

    private static DayOfWeek day(String s) {
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "MONDAY", "LUNES" -> DayOfWeek.MONDAY;
            case "TUESDAY", "MARTES" -> DayOfWeek.TUESDAY;
            case "WEDNESDAY", "MIERCOLES", "MIÉRCOLES" -> DayOfWeek.WEDNESDAY;
            case "THURSDAY", "JUEVES" -> DayOfWeek.THURSDAY;
            case "FRIDAY", "VIERNES" -> DayOfWeek.FRIDAY;
            case "SATURDAY", "SABADO", "SÁBADO" -> DayOfWeek.SATURDAY;
            case "SUNDAY", "DOMINGO" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /** Siguiente hora de aparicion del dragon estrictamente posterior a 'after' (epoch ms). */
    private long nextAfter(long after) {
        ZonedDateTime base = Instant.ofEpochMilli(after).atZone(zone());
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("H:mm");
        long best = Long.MAX_VALUE;
        for (String entry : getConfig().getStringList("schedule.times")) {
            try {
                String[] t = entry.trim().split("\\s+");
                LocalTime time = LocalTime.parse(t[1], tf);
                String d = t[0].toUpperCase(Locale.ROOT);
                ZonedDateTime cand;
                if (d.equals("DAILY") || d.equals("DIARIO") || d.equals("TODOS")) {
                    cand = base.with(time);
                    if (!cand.isAfter(base)) cand = cand.plusDays(1).with(time);
                } else {
                    DayOfWeek dow = day(d);
                    if (dow == null) { getLogger().warning("Dia no valido en schedule.times: " + entry); continue; }
                    cand = base.with(TemporalAdjusters.nextOrSame(dow)).with(time);
                    if (!cand.isAfter(base)) cand = cand.plusWeeks(1).with(time);
                }
                best = Math.min(best, cand.toInstant().toEpochMilli());
            } catch (Exception e) {
                getLogger().warning("Entrada no valida en schedule.times: " + entry);
            }
        }
        if (best == Long.MAX_VALUE) { // sin entradas validas: sabado 20:00
            ZonedDateTime cand = base.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).with(LocalTime.of(20, 0));
            if (!cand.isAfter(base)) cand = cand.plusWeeks(1).with(LocalTime.of(20, 0));
            best = cand.toInstant().toEpochMilli();
        }
        return best;
    }

    /** Proxima aparicion: primera hora del horario posterior al ultimo spawn. */
    long nextAppearMs() { return nextAfter(state.getLong("lastSpawn")); }

    long msToNext() { return nextAppearMs() - System.currentTimeMillis(); }

    static String fmt(long ms) {
        if (ms <= 0) return "0s";
        long s = ms / 1000, d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    String nextText() { return fmt(msToNext()); }

    String nextDateText() {
        return DateTimeFormatter.ofPattern("dd/MM HH:mm").format(Instant.ofEpochMilli(nextAppearMs()).atZone(zone()));
    }

    // ------------------------------------------------------------------ tarea periodica (cada 10 ticks)
    private void tick() {
        World end = endWorld();
        if (end != null) {
            DragonBattle b = end.getEnderDragonBattle();
            if (b != null) b.getBossBar().setVisible(false); // nuestro dragon va con bossbar propia
        }
        updateBar();
        if (++tickCount % 30 == 0 && damageDirty) saveDamage();
        if (tickCount % 2 != 0) return;

        long left = msToNext();
        if (left <= 0) {
            long late = -left;
            boolean ok = late <= getConfig().getLong("schedule.catch-up-minutes", 30) * MIN;
            state.set("lastSpawn", System.currentTimeMillis());
            saveState();
            warned.clear();
            if (!ok) getLogger().info("Hora de spawn perdida (servidor apagado): se salta hasta la siguiente.");
            else if (!spawnDragon()) getLogger().info("No se pudo hacer aparecer al dragon (ya hay uno vivo o no hay End).");
            return;
        }
        List<Integer> ws = new ArrayList<>(getConfig().getIntegerList("schedule.warn-minutes"));
        Collections.sort(ws);
        for (int w : ws) {
            if (left <= w * MIN) {
                if (!warned.contains(w)) {
                    for (int x : ws) if (x >= w) warned.add(x);
                    broadcast(msg("warn").replace("%minutes%", String.valueOf(w)));
                }
                break;
            }
        }
    }

    /** Hace aparecer al dragon en el End (sin reiniciar nada). */
    boolean spawnDragon() {
        World end = endWorld();
        if (end == null || dragonAlive()) return false;
        double x = getConfig().getDouble("spawn.x", 0.5), y = getConfig().getDouble("spawn.y", 90), z = getConfig().getDouble("spawn.z", 0.5);
        Location loc = new Location(end, x, y, z);
        end.getChunkAt(loc); // carga el chunk
        if (getConfig().getBoolean("crystals.regenerate", true)) regenCrystals(end);
        state.set("spawnAllowed", true);
        end.spawn(loc, EnderDragon.class);
        state.set("spawnAllowed", false); // por si el spawn fue cancelado por otro plugin
        return true;
    }

    /** Regenera los cristales de los pilares de obsidiana (si el combate vanilla del mundo esta disponible). */
    boolean regenCrystals(World end) {
        DragonBattle b = end.getEnderDragonBattle();
        if (b == null) return false;
        b.resetCrystals();
        return true;
    }

    // ------------------------------------------------------------------ bossbar propia
    private void updateBar() {
        EnderDragon d = dragon();
        if (d == null) { hideBar(); return; }
        double max = Math.max(1.0, d.getMaxHealth());
        double hp = Math.max(0.0, d.getHealth());
        if (bar == null) {
            bar = BossBar.bossBar(Component.empty(), 1f, parse(getConfig().getString("bossbar.color", "PINK"), BossBar.Color.PINK),
                    parse(getConfig().getString("bossbar.style", "PROGRESS"), BossBar.Overlay.PROGRESS));
        }
        String title = getConfig().getString("bossbar.title", "&5&lDragón del End &f%health%/%max%")
                .replace("%health%", String.valueOf((int) Math.ceil(hp))).replace("%max%", String.valueOf((int) Math.ceil(max)));
        bar.name(LEG.deserialize(title));
        bar.progress((float) Math.min(1.0, hp / max));
        double radius = getConfig().getDouble("bossbar.radius", 300);
        Set<UUID> now = new HashSet<>();
        for (Player p : d.getWorld().getPlayers()) {
            boolean near = radius <= 0 || p.getLocation().distanceSquared(d.getLocation()) <= radius * radius;
            if (near) { now.add(p.getUniqueId()); if (viewers.add(p.getUniqueId())) p.showBossBar(bar); }
        }
        for (Iterator<UUID> it = viewers.iterator(); it.hasNext(); ) {
            UUID u = it.next();
            if (now.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
            it.remove();
        }
    }

    private void hideBar() {
        if (bar == null) return;
        for (UUID u : viewers) { Player p = Bukkit.getPlayer(u); if (p != null) p.hideBossBar(bar); }
        viewers.clear();
        bar = null;
    }

    private static <T extends Enum<T>> T parse(String v, T def) {
        try { return Enum.valueOf(def.getDeclaringClass(), v.toUpperCase(Locale.ROOT)); } catch (Exception e) { return def; }
    }

    // ------------------------------------------------------------------ helpers de mundo/dragon
    World endWorld() {
        String n = getConfig().getString("end-world", "");
        if (n != null && !n.isBlank()) return Bukkit.getWorld(n);
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == World.Environment.THE_END) return w;
        return null;
    }

    private World safeWorld() {
        World w = Bukkit.getWorld(getConfig().getString("safe-world", "survival"));
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    private boolean isOurs(Entity e) {
        return e instanceof EnderDragon d && d.getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    private EnderDragon resolve(Entity e) {
        if (e instanceof EnderDragon d) return isOurs(d) ? d : null;
        if (e instanceof ComplexEntityPart part && part.getParent() instanceof EnderDragon d) return isOurs(d) ? d : null;
        return null;
    }

    private EnderDragon dragon() {
        if (dragonUuid == null) return null;
        return Bukkit.getEntity(dragonUuid) instanceof EnderDragon d && !d.isDead() ? d : null;
    }

    boolean dragonAlive() { return dragon() != null; }

    String dragonHealthText(String none) {
        EnderDragon d = dragon();
        return d == null ? none : num(d.getHealth()) + "/" + num(d.getMaxHealth());
    }

    String statusText() {
        return dragonAlive() ? "vivo" : "esperando";
    }

    static String num(double v) { return String.format(Locale.US, "%.1f", v); }

    private String msg(String k) { return getConfig().getString("messages." + k, ""); }

    private void broadcast(String legacy) {
        if (legacy == null || legacy.isBlank()) return;
        Bukkit.broadcast(LEG.deserialize(legacy));
    }

    // ------------------------------------------------------------------ eventos
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof EnderDragon d)) return;
        if (d.getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE)) return;
        if (!state.getBoolean("spawnAllowed", false)) { e.setCancelled(true); return; } // solo el plugin (horario o /endregen spawn) puede crear al dragon
        state.set("spawnAllowed", false);
        d.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        double hp = Math.max(1, getConfig().getDouble("dragon.health", 200));
        AttributeInstance ai = d.getAttribute(Attribute.MAX_HEALTH);
        if (ai != null) { ai.setBaseValue(hp); d.setHealth(Math.min(hp, ai.getValue())); }
        dragonUuid = d.getUniqueId();
        state.set("dragonUuid", dragonUuid.toString());
        saveState();
        damage.clear(); names.clear(); damageDirty = true; saveDamage();
        broadcast(msg("dragon-spawned"));
        // Si el combate vanilla adopta a este dragon, que al morir no ponga el huevo como bloque.
        Bukkit.getScheduler().runTask(this, () -> {
            DragonBattle b = d.getWorld().getEnderDragonBattle();
            if (b != null) b.setPreviouslyKilled(true);
        });
    }

    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof EnderDragon d && isOurs(d) && !d.isDead()) {
                dragonUuid = d.getUniqueId();
                state.set("dragonUuid", dragonUuid.toString());
                saveState();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageToPlayer(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.DRAGON_BREATH) {
            if (dragonAlive()) e.setDamage(e.getDamage() * getConfig().getDouble("dragon.damage.breath-multiplier", 1.0));
            return;
        }
        if (e instanceof EntityDamageByEntityEvent be && isOurs(be.getDamager())) {
            e.setDamage(e.getDamage() * getConfig().getDouble("dragon.damage.melee-multiplier", 1.0));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonHurt(EntityDamageByEntityEvent e) {
        EnderDragon d = resolve(e.getEntity());
        if (d == null) return;
        Player p = null;
        if (e.getDamager() instanceof Player pl) p = pl;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl) p = pl;
        if (p == null) return;
        double amt = Math.min(e.getFinalDamage(), d.getHealth());
        if (amt <= 0) return;
        damage.merge(p.getUniqueId(), amt, Double::sum);
        names.put(p.getUniqueId(), p.getName());
        damageDirty = true;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!isOurs(e.getEntity())) return;
        World w = e.getEntity().getWorld();
        dragonUuid = null;
        state.set("dragonUuid", null);
        saveState();
        hideBar();
        handleDeath(w, e.getEntity().getKiller());
        // Abre el portal de salida (sin huevo-bloque) cuando termina la animacion de muerte.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            DragonBattle b = w.getEnderDragonBattle();
            if (b != null) { b.setPreviouslyKilled(true); b.generateEndPortal(true); }
        }, 240L);
    }

    // ---- Items "solo para tradeos" (no-use): no se pueden comer, craftear ni echar al compostador ----
    private boolean noUse(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(noUseKey, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (noUse(e.getItem())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (noUse(it)) { e.getInventory().setResult(null); return; }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onComposter(PlayerInteractEvent e) {
        if (e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.COMPOSTER && noUse(e.getItem())) e.setCancelled(true);
    }

    // ---- Sesion 2026-11-26 (a peticion del usuario: "deshabilitar portales del end en mundo normal") ----
    // Cancela la entrada al End por portal desde cualquier mundo de tipo NORMAL. La vuelta (End -> overworld) no se toca.
    private boolean blockedEndPortal(Location from) {
        return getConfig().getBoolean("portals.block-end-portal-from-overworld", true)
                && from != null && from.getWorld() != null
                && from.getWorld().getEnvironment() == World.Environment.NORMAL;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL || !blockedEndPortal(e.getFrom())) return;
        e.setCancelled(true);
        String m = msg("end-portal-disabled");
        if (m != null && !m.isBlank()) e.getPlayer().sendMessage(LEG.deserialize(m));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (e.getTo() != null && e.getTo().getWorld() != null && e.getTo().getWorld().getEnvironment() == World.Environment.THE_END
                && blockedEndPortal(e.getFrom())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> deliverPending(p), 40L);
    }

    // ------------------------------------------------------------------ muerte, top y drops
    List<Map.Entry<UUID, Double>> ranking() {
        List<Map.Entry<UUID, Double>> l = new ArrayList<>(damage.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return l;
    }

    double totalDamage() { double t = 0; for (double v : damage.values()) t += v; return t; }
    double damageOf(UUID u) { return damage.getOrDefault(u, 0.0); }
    double percentOf(double v) { double t = totalDamage(); return t <= 0 ? 0 : v * 100.0 / t; }
    String nameOf(UUID u) {
        String n = names.get(u);
        if (n == null) n = Bukkit.getOfflinePlayer(u).getName();
        return n == null ? u.toString().substring(0, 8) : n;
    }

    private record Reward(String id, String display, ItemStack item, List<String> commands) { }

    private void handleDeath(World w, Player killer) {
        purgeEgg(w);
        List<Map.Entry<UUID, Double>> rank = ranking();
        if (rank.isEmpty()) return;
        int topSize = Math.max(1, getConfig().getInt("rewards.top-size", 3));
        List<Map.Entry<UUID, Double>> top = rank.subList(0, Math.min(topSize, rank.size()));

        broadcast(msg("divider"));
        if (killer != null) broadcast(msg("killed").replace("%player%", killer.getName()));
        else broadcast(msg("killed-nokiller"));
        String[] medals = {"🥇", "🥈", "🥉"};
        int pos = 1;
        for (Map.Entry<UUID, Double> en : top) {
            broadcast(msg("death-line").replace("%medal%", pos <= 3 ? medals[pos - 1] : "#" + pos)
                    .replace("%player%", nameOf(en.getKey()))
                    .replace("%damage%", String.valueOf(Math.round(en.getValue())))
                    .replace("%percent%", num(percentOf(en.getValue()))));
            pos++;
        }

        // Tiradas
        List<Reward> won = new ArrayList<>();
        ConfigurationSection ds = getConfig().getConfigurationSection("drops");
        if (ds != null) {
            for (String key : ds.getKeys(false)) {
                ConfigurationSection s = ds.getConfigurationSection(key);
                if (s == null) continue;
                double chance = s.getDouble("chance", 100);
                if (rnd.nextDouble() * 100.0 >= chance) continue;
                int mn = Math.max(0, s.getInt("min", s.getInt("rolls", 1)));
                int mx = Math.max(mn, s.getInt("max", mn));
                int units = mn + rnd.nextInt(mx - mn + 1);
                for (int i = 0; i < units; i++) {
                    Reward r = buildReward(key, s);
                    if (r != null) won.add(r);
                }
            }
        }

        // Reparto ponderado por % de dano entre el top
        broadcast(won.isEmpty() ? msg("no-drops") : msg("drops-header"));
        boolean unique = getConfig().getBoolean("rewards.unique-winners", true);
        List<UUID> topIds = new ArrayList<>();
        for (Map.Entry<UUID, Double> en : top) topIds.add(en.getKey());
        List<UUID> pool = new ArrayList<>();
        for (Reward r : won) {
            if (pool.isEmpty() || !unique) { pool.clear(); pool.addAll(topIds); }
            UUID winner = pick(pool);
            if (unique) pool.remove(winner);
            broadcast(msg("drop-line").replace("%player%", nameOf(winner)).replace("%drop%", r.display()));
            deliver(winner, r);
        }
        broadcast(msg("divider"));
        damage.clear(); names.clear(); damageDirty = true; saveDamage();
    }

    private UUID pick(List<UUID> pool) {
        double sum = 0;
        for (UUID u : pool) sum += Math.max(damageOf(u), 0.0001);
        double r = rnd.nextDouble() * sum;
        for (UUID u : pool) { r -= Math.max(damageOf(u), 0.0001); if (r <= 0) return u; }
        return pool.get(pool.size() - 1);
    }

    private Reward buildReward(String key, ConfigurationSection s) {
        String display = s.getString("display", key);
        String type = s.getString("type", "ITEM").toUpperCase(Locale.ROOT);
        if (type.equals("COMMAND")) {
            List<String> cmds = s.getStringList("commands");
            return cmds.isEmpty() ? null : new Reward(key, display, null, cmds);
        }
        Material m = Material.matchMaterial(s.getString("material", "DIRT"));
        if (m == null) { getLogger().warning("Drop '" + key + "': material no valido."); return null; }
        ItemStack it = new ItemStack(m, Math.max(1, s.getInt("amount", 1)));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = s.getString("name");
            if (name != null) meta.displayName(LEG.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<String> lore = s.getStringList("lore");
            if (!lore.isEmpty()) {
                List<Component> lc = new ArrayList<>();
                for (String l : lore) lc.add(LEG.deserialize(l).decoration(TextDecoration.ITALIC, false));
                meta.lore(lc);
            }
            for (String en : s.getStringList("enchants")) { // formato SHARPNESS:5
                String[] sp = en.split(":");
                Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(sp[0].toLowerCase(Locale.ROOT)));
                if (ench != null) meta.addEnchant(ench, sp.length > 1 ? Integer.parseInt(sp[1]) : 1, true);
            }
            if (s.getBoolean("glint", false)) meta.setEnchantmentGlintOverride(true);
            if (s.getBoolean("no-use", false)) meta.getPersistentDataContainer().set(noUseKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return new Reward(key, display, it, List.of());
    }

    private void deliver(UUID u, Reward r) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) { give(p, r.item(), r.commands(), r.display()); return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pendingFile);
        List<Map<?, ?>> list = new ArrayList<>(y.getMapList(u.toString()));
        Map<String, Object> m = new HashMap<>();
        m.put("item", r.item());
        m.put("commands", r.commands());
        m.put("display", r.display());
        list.add(m);
        y.set(u.toString(), list);
        try { y.save(pendingFile); } catch (IOException ex) { getLogger().severe("pending.yml: " + ex); }
    }

    private void deliverPending(Player p) {
        if (!p.isOnline() || !pendingFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pendingFile);
        String k = p.getUniqueId().toString();
        if (!y.contains(k)) return;
        List<Map<?, ?>> list = y.getMapList(k);
        y.set(k, null);
        try { y.save(pendingFile); } catch (IOException ex) { getLogger().severe("pending.yml: " + ex); }
        p.sendMessage(LEG.deserialize(msg("reward-pending")));
        for (Map<?, ?> m : list) {
            ItemStack it = m.get("item") instanceof ItemStack i ? i : null;
            List<String> cmds = new ArrayList<>();
            if (m.get("commands") instanceof List<?> l) for (Object o : l) cmds.add(String.valueOf(o));
            give(p, it, cmds, String.valueOf(m.get("display")));
        }
    }

    private void give(Player p, ItemStack item, List<String> cmds, String display) {
        if (item != null) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(item.clone());
            for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
        }
        for (String c : cmds) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
        p.sendMessage(LEG.deserialize(msg("reward-received").replace("%drop%", display)));
    }

    /** Quita el huevo-bloque si algo lo coloca sobre el portal (por seguridad; ya se evita con previouslyKilled). */
    private void purgeEgg(World w) {
        final int[] n = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (++n[0] > 60) { task.cancel(); return; }
            DragonBattle b = w.getEnderDragonBattle();
            Location l = b == null ? null : b.getEndPortalLocation();
            if (l == null) return;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 8; dy++) {
                var blk = w.getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
                if (blk.getType() == Material.DRAGON_EGG) blk.setType(Material.AIR);
            }
        }, 20L, 10L);
    }

    // ------------------------------------------------------------------ persistencia
    private void saveState() {
        try { state.save(stateFile); } catch (IOException e) { getLogger().severe("state.yml: " + e); }
    }

    private void saveDamage() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Double> en : damage.entrySet()) {
            y.set(en.getKey() + ".damage", en.getValue());
            y.set(en.getKey() + ".name", names.get(en.getKey()));
        }
        try { y.save(damageFile); damageDirty = false; } catch (IOException e) { getLogger().severe("damage.yml: " + e); }
    }

    private void loadDamage() {
        if (!damageFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(damageFile);
        for (String k : y.getKeys(false)) {
            try {
                UUID u = UUID.fromString(k);
                damage.put(u, y.getDouble(k + ".damage"));
                String n = y.getString(k + ".name");
                if (n != null) names.put(u, n);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    // ------------------------------------------------------------------ comandos
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("endregen.admin")) { s.sendMessage("§cNo tienes permiso."); return true; }
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                s.sendMessage("§5EndRegen §7estado: §f" + statusText());
                s.sendMessage("§7Proxima aparicion: §f" + nextDateText() + " §7(en " + fmt(msToNext()) + ")");
                s.sendMessage("§7Vida del dragon: §f" + dragonHealthText("-"));
            }
            case "top" -> {
                List<Map.Entry<UUID, Double>> r = ranking();
                if (r.isEmpty()) { s.sendMessage("§7Sin daño registrado."); return true; }
                int i = 1;
                for (Map.Entry<UUID, Double> en : r.subList(0, Math.min(10, r.size())))
                    s.sendMessage("§e#" + i++ + " §f" + nameOf(en.getKey()) + " §7- §c" + num(en.getValue())
                            + " §7(§e" + num(percentOf(en.getValue())) + "%§7)");
            }
            case "spawn" -> {
                if (spawnDragon()) s.sendMessage("§dDragon generado (no altera el horario).");
                else s.sendMessage("§cNo se pudo: ya hay un dragon vivo o no hay mundo del End.");
            }
            case "remove" -> {
                EnderDragon d = dragon();
                if (d == null) { s.sendMessage("§7No hay dragon vivo."); return true; }
                dragonUuid = null; state.set("dragonUuid", null); saveState();
                hideBar();
                d.remove(); // remove() no dispara muerte: no hay drops
                damage.clear(); names.clear(); damageDirty = true;
                s.sendMessage("§aDragon retirado (sin drops).");
            }
            case "crystals" -> {
                World end = endWorld();
                s.sendMessage(end != null && regenCrystals(end) ? "§dCristales regenerados." : "§cNo se pudo (sin End o sin combate vanilla).");
            }
            case "give" -> { // /endregen give <jugador> <idDrop> [cantidad]
                if (a.length < 3) { s.sendMessage("§c/endregen give <jugador> <idDrop> [cantidad]"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                ConfigurationSection ds = getConfig().getConfigurationSection("drops." + a[2]);
                if (t == null || ds == null) { s.sendMessage("§cJugador offline o drop inexistente."); return true; }
                Reward r = buildReward(a[2], ds);
                if (r == null || r.item() == null) { s.sendMessage("§cEse drop no es de tipo ITEM."); return true; }
                int n = 1;
                try { if (a.length > 3) n = Math.max(1, Math.min(2304, Integer.parseInt(a[3]))); } catch (NumberFormatException ex) { }
                ItemStack it = r.item();
                int max = it.getMaxStackSize();
                while (n > 0) {
                    ItemStack part = it.clone();
                    part.setAmount(Math.min(n, max));
                    n -= part.getAmount();
                    for (ItemStack left : t.getInventory().addItem(part).values()) t.getWorld().dropItemNaturally(t.getLocation(), left);
                }
                s.sendMessage("§aEntregado a " + t.getName() + ".");
            }
            case "reload" -> { reloadConfig(); s.sendMessage("§aConfig recargada."); }
            default -> s.sendMessage("§c/endregen <status|top|spawn|remove|crystals|give|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("endregen.admin")) return List.of();
        if (a.length == 1) return List.of("status", "top", "spawn", "remove", "crystals", "give", "reload");
        return List.of();
    }
}
