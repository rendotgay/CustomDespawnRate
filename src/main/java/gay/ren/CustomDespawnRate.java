package gay.ren;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class CustomDespawnRate extends JavaPlugin implements Listener {

    private final Map<Material, Integer> despawnSeconds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> trackedDeadlines = new ConcurrentHashMap<>();
    private volatile boolean debug = false;
    private volatile BukkitTask scannerTask = null;
    private static final long SCAN_INTERVAL_TICKS = 20L; // 1 second

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);

        if (despawnSeconds.isEmpty()) {
            getLogger().info("DespawnRate: No items configured in config.yml");
        } else {
            for (Map.Entry<Material, Integer> e : despawnSeconds.entrySet()) {
                getLogger().info("DespawnRate: configured -> " + e.getKey().name() + " = " + e.getValue() + "seconds.");
            }
        }

        Bukkit.getScheduler().runTask(this, () -> {
            long now = System.currentTimeMillis();
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                for (Item item : w.getEntitiesByClass(Item.class)) {
                    maybeTrackItem(item, now);
                }
            }
            if (!trackedDeadlines.isEmpty()) ensureScannerRunning();
        });
    }

    @Override
    public void onDisable() {
        cancelScanner();
        trackedDeadlines.clear();
        getLogger().info("DespawnRate disabled.");
    }

    private void loadConfig() {
        despawnSeconds.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("items");
        if (section == null) {
            getLogger().warning("DespawnRate: no 'items' section in config.yml");
            return;
        }
        Set<String> keys = section.getKeys(false);
        for (String key : keys) {
            int seconds = getConfig().getInt("items." + key, 0);
            Material mat = Material.matchMaterial(key);
            if (mat == null) {
                getLogger().warning("DespawnRate: unknown material in config.yml: " + key);
                continue;
            }
            if (seconds <= 0) {
                getLogger().warning("DespawnRate: invalid seconds for " + key + " (must be >0). Skipping.");
                continue;
            }
            despawnSeconds.put(mat, seconds);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("despawnrate")) return false;
        if (args.length == 1) {
            if (args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadConfig();
                trackedDeadlines.clear();
                long now = System.currentTimeMillis();
                Bukkit.getWorlds().forEach(w -> w.getEntitiesByClass(Item.class).forEach(i -> maybeTrackItem(i, now)));
                sender.sendMessage("[DespawnRate] config reloaded.");
                return true;
            } else if (args[0].equalsIgnoreCase("debug")) {
                debug = !debug;
                sender.sendMessage("[DespawnRate] debug set to " + debug);
                getLogger().info("DespawnRate: debug=" + debug + " (by " + sender.getName() + ")");
                return true;
            }
        }
        sender.sendMessage("Usage: /despawnrate reload | debug");
        return true;
    }

    private boolean maybeTrackItem(Item item, long now) {
        if (item == null || !item.isValid() || item.isDead()) {
            if (debug) getLogger().fine("maybeTrackItem skipped invalid item");
            return false;
        }
        Material mat = item.getItemStack().getType();
        Integer secs = despawnSeconds.get(mat);
        if (secs == null) {
            if (debug) getLogger().finer("Not configured, skipping: " + mat);
            return false;
        }
        long deadline = now + secs * 1000L;
        trackedDeadlines.put(item.getUniqueId(), deadline);
        if (debug) getLogger().info("tracking " + item.getUniqueId() + " " + mat + " -> " + secs + "s");
        ensureScannerRunning();
        return true;
    }

    private synchronized void ensureScannerRunning() {
        if (scannerTask != null) return;
        if (trackedDeadlines.isEmpty()) return;
        scannerTask = Bukkit.getScheduler().runTaskTimer(this, this::scanTrackedItems, SCAN_INTERVAL_TICKS, SCAN_INTERVAL_TICKS);
        if (debug) getLogger().fine("scanner started");
    }

    private synchronized void cancelScanner() {
        if (scannerTask != null) {
            scannerTask.cancel();
            scannerTask = null;
            if (debug) getLogger().fine("scanner cancelled");
        }
    }

    private void scanTrackedItems() {
        long now = System.currentTimeMillis();
        UUID[] ids = trackedDeadlines.keySet().toArray(new UUID[0]);
        for (UUID id : ids) {
            Long deadline = trackedDeadlines.get(id);
            if (deadline == null) continue;
            if (deadline <= now) {
                Item item = findItemByUUID(id);
                trackedDeadlines.remove(id);
                if (item != null && item.isValid() && !item.isDead()) {
                    try {
                        if (debug) getLogger().info("removing expired item " + id);
                        item.remove();
                    } catch (Throwable t) {
                        getLogger().warning("Failed removing expired item " + id + ": " + t.getMessage());
                    }
                } else {
                    if (debug) getLogger().finer("expired tracked id not present: " + id);
                }
            } else {
                Item maybe = findItemByUUID(id);
                if (maybe == null) {
                    trackedDeadlines.remove(id);
                    if (debug) getLogger().finer("tracked entity gone, removed: " + id);
                }
            }
        }
        if (trackedDeadlines.isEmpty()) cancelScanner();
    }

    private Item findItemByUUID(UUID id) {
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            try {
                org.bukkit.entity.Entity e = w.getEntity(id);
                if (e instanceof Item) return (Item) e;
            } catch (Throwable ignored) {}
        }
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (Item item : w.getEntitiesByClass(Item.class)) {
                if (item.getUniqueId().equals(id)) return item;
            }
        }
        return null;
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Item it = event.getEntity();
        Bukkit.getScheduler().runTaskLater(this, () -> maybeTrackItem(it, System.currentTimeMillis()), 1L);
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Item it = event.getItemDrop();
        Bukkit.getScheduler().runTaskLater(this, () -> maybeTrackItem(it, System.currentTimeMillis()), 1L);
    }

    @EventHandler
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        trackedDeadlines.remove(event.getItem().getUniqueId());
        if (debug) getLogger().finer("pickup removed tracking for " + event.getItem().getUniqueId());
    }

    @EventHandler
    public void onItemMerge(ItemMergeEvent event) {
        Item source = event.getEntity();
        Item target = event.getTarget();
        UUID sId = source.getUniqueId();
        UUID tId = target.getUniqueId();
        Long sDeadline = trackedDeadlines.remove(sId);
        Long tDeadline = trackedDeadlines.get(tId);
        long now = System.currentTimeMillis();
        long newDeadline = now + 300_000L;
        if (sDeadline != null && tDeadline != null) newDeadline = Math.min(sDeadline, tDeadline);
        else if (sDeadline != null) newDeadline = sDeadline;
        else if (tDeadline != null) newDeadline = tDeadline;
        trackedDeadlines.put(tId, newDeadline);
        if (debug) getLogger().finer("merge src=" + sId + " -> tgt=" + tId);
        ensureScannerRunning();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (debug && event.getPlayer().isOp()) {
            event.getPlayer().sendMessage("[DespawnRate] debug=" + debug + " tracked=" + trackedDeadlines.size());
        }
    }
}