package hu.donutcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** /rtp, /spawn, /setspawn, /tpa, /tpaccept, /tpdeny with a stand-still countdown. */
public final class Teleports implements TabExecutor, Listener {
    private static final long TPA_EXPIRY_MS = 60_000;

    private record Pending(Location start, BukkitTask task) {
    }

    private record Request(UUID from, long time) {
    }

    private final DonutCore plugin;
    private final Random random = new Random();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> rtpCooldown = new HashMap<>();
    private final Map<UUID, Request> requests = new HashMap<>(); // target -> request

    public Teleports(DonutCore plugin) {
        this.plugin = plugin;
    }

    // ---- countdown ----

    private void countdown(Player p, Runnable action) {
        cancel(p.getUniqueId(), false);
        if (p.hasPermission("donutcore.staff") || plugin.rankOf(p.getUniqueId()).equals("staff")
                || plugin.rankOf(p.getUniqueId()).equals("admin") || plugin.rankOf(p.getUniqueId()).equals("owner")) {
            action.run();
            return;
        }
        final int total = Math.max(0, plugin.getConfig().getInt("teleport.delay-seconds", 3));
        if (total == 0) {
            action.run();
            return;
        }
        final int[] left = {total};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                cancel(p.getUniqueId(), false);
                return;
            }
            if (left[0] <= 0) {
                pending.remove(p.getUniqueId()).task().cancel();
                action.run();
                return;
            }
            p.sendActionBar(Util.c("&eTeleporting in &6" + left[0] + "&es... &7(don't move)"));
            left[0]--;
        }, 0L, 20L);
        pending.put(p.getUniqueId(), new Pending(p.getLocation(), task));
    }

    private void cancel(UUID id, boolean notify) {
        Pending pd = pending.remove(id);
        if (pd != null) {
            pd.task().cancel();
            Player p = Bukkit.getPlayer(id);
            if (notify && p != null) {
                p.sendActionBar(Util.c("&cTeleport cancelled."));
                Util.msg(p, "&cTeleport cancelled.");
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Pending pd = pending.get(e.getPlayer().getUniqueId());
        if (pd == null || e.getTo() == null) return;
        Location a = pd.start();
        Location b = e.getTo();
        if (a.getWorld() != b.getWorld() || a.distanceSquared(b) > 0.25) {
            cancel(e.getPlayer().getUniqueId(), true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && pending.containsKey(p.getUniqueId())) {
            cancel(p.getUniqueId(), true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer().getUniqueId(), false);
        requests.remove(e.getPlayer().getUniqueId());
    }

    // ---- commands ----

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            if (cmd.getName().equalsIgnoreCase("setspawn")) {
                Util.msg(sender, "&cOnly players can set the spawn.");
            } else {
                Util.msg(sender, "&cOnly players can teleport.");
            }
            return true;
        }
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (name.equals("setspawn")) {
            if (!p.hasPermission("donutcore.admin")) {
                Util.msg(p, "&cYou do not have permission.");
                return true;
            }
            plugin.data.setSpawn(p.getLocation());
            plugin.data.save(false);
            Util.msg(p, "&aSpawn set.");
            return true;
        }

        if (name.equals("tpaccept") || name.equals("tpdeny")) {
            Request r = requests.remove(p.getUniqueId());
            if (r == null || System.currentTimeMillis() - r.time() > TPA_EXPIRY_MS) {
                Util.msg(p, "&cYou have no pending teleport request.");
                return true;
            }
            Player from = Bukkit.getPlayer(r.from());
            if (from == null) {
                Util.msg(p, "&cThat player is no longer online.");
                return true;
            }
            if (name.equals("tpdeny")) {
                Util.msg(p, "&cRequest denied.");
                Util.msg(from, "&c" + p.getName() + " denied your teleport request.");
                return true;
            }
            if (plugin.combat.isTagged(from)) {
                Util.msg(from, "&cYou cannot teleport while in combat.");
                Util.msg(p, "&c" + from.getName() + " is in combat and cannot teleport.");
                return true;
            }
            Util.msg(p, "&aRequest accepted.");
            Util.msg(from, "&a" + p.getName() + " accepted your request.");
            countdown(from, () -> {
                if (from.isOnline() && p.isOnline()) from.teleportAsync(p.getLocation());
            });
            return true;
        }

        if (plugin.combat.isTagged(p)) {
            Util.msg(p, "&cYou cannot teleport while in combat.");
            return true;
        }

        switch (name) {
            case "spawn": {
                Location spawn = plugin.data.getSpawn();
                if (spawn == null) {
                    World w = Bukkit.getWorlds().get(0);
                    spawn = w.getSpawnLocation();
                }
                final Location dest = spawn;
                countdown(p, () -> {
                    p.teleportAsync(dest);
                    Util.msg(p, "&aTeleported to spawn.");
                });
                return true;
            }
            case "tpa": {
                if (args.length < 1) {
                    Util.msg(p, "&cUsage: /tpa <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null || target.equals(p)) {
                    Util.msg(p, "&cPlayer not found.");
                    return true;
                }
                requests.put(target.getUniqueId(), new Request(p.getUniqueId(), System.currentTimeMillis()));
                Util.msg(p, "&aTeleport request sent to &6" + target.getName() + "&a.");
                Util.msg(target, "&6" + p.getName() + " &awants to teleport to you. &e/tpaccept &aor &e/tpdeny &7(60s)");
                return true;
            }
            case "rtp":
                randomTeleport(p);
                return true;
            default:
                return false;
        }
    }

    // ---- RTP ----

    private void randomTeleport(Player p) {
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("rtp.cooldown-seconds", 30) * 1000L;
        Long last = rtpCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd && !p.hasPermission("donutcore.staff")) {
            Util.msg(p, "&cWait &6" + Util.duration((cd - (now - last)) / 1000 + 1) + " &cbefore using /rtp again.");
            return;
        }
        World world = Bukkit.getWorld(plugin.getConfig().getString("rtp.world", "world"));
        if (world == null) {
            Util.msg(p, "&cThe RTP world is not configured correctly.");
            return;
        }
        countdown(p, () -> {
            Util.msg(p, "&7Searching for a safe location...");
            tryRtp(p, world, 0);
        });
    }

    private void tryRtp(Player p, World world, int attempt) {
        if (!p.isOnline()) return;
        if (attempt >= 12) {
            Util.msg(p, "&cCould not find a safe spot, try again.");
            return;
        }
        int min = plugin.getConfig().getInt("rtp.min-radius", 250);
        int max = Math.max(min + 1, plugin.getConfig().getInt("rtp.max-radius", 5000));
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = min + random.nextDouble() * (max - min);
        int x = (int) Math.round(Math.cos(angle) * dist);
        int z = (int) Math.round(Math.sin(angle) * dist);

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            // Paper completes this on the main thread
            if (!p.isOnline()) return;
            int y = world.getHighestBlockYAt(x, z);
            Material ground = world.getBlockAt(x, y, z).getType();
            if (!ground.isSolid() || ground == Material.BEDROCK || y <= world.getMinHeight()
                    || !world.getBlockAt(x, y + 1, z).isPassable() || !world.getBlockAt(x, y + 2, z).isPassable()
                    || !world.getWorldBorder().isInside(new Location(world, x, y, z))) {
                tryRtp(p, world, attempt + 1);
                return;
            }
            Location dest = new Location(world, x + 0.5, y + 1, z + 0.5);
            p.teleportAsync(dest).thenAccept(ok -> {
                if (ok) {
                    rtpCooldown.put(p.getUniqueId(), System.currentTimeMillis());
                    Util.msg(p, "&aTeleported to &f" + x + ", " + (y + 1) + ", " + z + "&a.");
                }
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("tpa") && args.length == 1) return Util.onlineNames(args[0]);
        return List.of();
    }
}
