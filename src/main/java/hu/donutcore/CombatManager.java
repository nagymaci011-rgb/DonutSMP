package hu.donutcore;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Combat tag: players who fight cannot log out safely or use teleport commands. */
public final class CombatManager implements Listener {
    private final DonutCore plugin;
    private final Map<UUID, Long> tagged = new HashMap<>();

    public CombatManager(DonutCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            tagged.entrySet().removeIf(e -> {
                if (e.getValue() <= now) {
                    Player p = Bukkit.getPlayer(e.getKey());
                    if (p != null) {
                        p.sendActionBar(Util.c("&aYou are no longer in combat."));
                    }
                    return true;
                }
                return false;
            });
            for (Map.Entry<UUID, Long> e : tagged.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    long left = (e.getValue() - now + 999) / 1000;
                    p.sendActionBar(Util.c("&cIn combat: &f" + left + "s"));
                }
            }
        }, 20L, 20L);
    }

    public boolean isTagged(Player p) {
        Long until = tagged.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private void tag(Player p) {
        long ms = plugin.getConfig().getLong("combat.seconds", 20) * 1000L;
        if (!isTagged(p)) {
            Util.msg(p, "&cYou are in combat! Do not log out for &f" + (ms / 1000) + "s&c.");
        }
        tagged.put(p.getUniqueId(), System.currentTimeMillis() + ms);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity damager = e.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (damager instanceof Player attacker && !attacker.equals(victim)) {
            tag(victim);
            tag(attacker);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!isTagged(e.getPlayer())) return;
        String raw = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (raw.contains(":")) raw = raw.substring(raw.indexOf(':') + 1);
        List<String> allowed = plugin.getConfig().getStringList("combat.allowed-commands");
        if (!allowed.contains(raw)) {
            e.setCancelled(true);
            Util.msg(e.getPlayer(), "&cYou cannot use that command while in combat.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (isTagged(p)) {
            // combat logger: kill them so the drops are not exploitable
            tagged.remove(p.getUniqueId());
            Bukkit.broadcast(Util.c("&c" + p.getName() + " logged out during combat and was killed."));
            p.setHealth(0);
        }
        tagged.remove(p.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        tagged.remove(e.getEntity().getUniqueId());
    }
}
