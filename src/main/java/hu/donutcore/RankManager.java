package hu.donutcore;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Ranks (member/staff/admin/owner), chat prefix, mute, join handling, kills/deaths and the sidebar. */
public final class RankManager implements TabExecutor, Listener {
    public static final List<String> RANKS = List.of("member", "staff", "admin", "owner");
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private final DonutCore plugin;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public RankManager(DonutCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) updateSidebar(p);
        }, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
    }

    public void shutdown() {
        for (Map.Entry<UUID, PermissionAttachment> e : attachments.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.removeAttachment(e.getValue());
        }
        attachments.clear();
    }

    // ---- rank helpers ----

    private int level(String rank) {
        int i = RANKS.indexOf(rank);
        return i < 0 ? 0 : i;
    }

    private String prefix(String rank) {
        return plugin.getConfig().getString("ranks." + rank + ".prefix", "");
    }

    /** Grants the donutcore.* permission nodes that belong to the player's rank. */
    public void apply(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) p.removeAttachment(old);
        PermissionAttachment att = p.addAttachment(plugin);
        int lvl = level(plugin.rankOf(p.getUniqueId()));
        if (lvl >= 1) att.setPermission("donutcore.staff", true);
        if (lvl >= 2) att.setPermission("donutcore.admin", true);
        if (lvl >= 3) att.setPermission("donutcore.owner", true);
        attachments.put(p.getUniqueId(), att);
        p.recalculatePermissions();
    }

    // ---- events ----

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        plugin.data.setS(id, "name", p.getName());
        if (plugin.data.getS(id, "rank", null) == null) plugin.data.setS(id, "rank", "member");
        apply(p);
        updateSidebar(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.ah.notifyClaims(p);
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        PermissionAttachment att = attachments.remove(e.getPlayer().getUniqueId());
        if (att != null) e.getPlayer().removeAttachment(att);
        plugin.data.save(false);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        plugin.data.setI(victim.getUniqueId(), "deaths", plugin.data.getI(victim.getUniqueId(), "deaths", 0) + 1);
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            plugin.data.setI(killer.getUniqueId(), "kills", plugin.data.getI(killer.getUniqueId(), "kills", 0) + 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (plugin.data.getI(p.getUniqueId(), "muted", 0) == 1) {
            e.setCancelled(true);
            Util.msg(p, "&cYou are muted.");
            return;
        }
        String pre = prefix(plugin.rankOf(p.getUniqueId()));
        e.renderer(ChatRenderer.viewerUnaware((source, name, message) ->
                Util.c(pre + "&f" + source.getName() + "&7: ").append(message.colorIfAbsent(NamedTextColor.WHITE))));
    }

    // ---- sidebar ----

    private void updateSidebar(Player p) {
        Scoreboard sb = p.getScoreboard();
        Objective obj = sb.getObjective("donutcore");
        if (obj == null || sb == Bukkit.getScoreboardManager().getMainScoreboard()) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            obj = sb.registerNewObjective("donutcore", "dummy", Util.c(plugin.getConfig().getString("sidebar-title", "&6DONUT SMP")));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            p.setScoreboard(sb);
        }
        for (String entry : sb.getEntries()) sb.resetScores(entry);

        UUID id = p.getUniqueId();
        long ticks = p.getStatistic(Statistic.PLAY_ONE_MINUTE);
        String[] lines = {
                "&r ",
                "&6Money: &a" + Util.money(plugin.eco.balance(id)),
                "&dShards: &f" + (long) plugin.eco.shards(id),
                "&cKills: &f" + plugin.data.getI(id, "kills", 0),
                "&7Deaths: &f" + plugin.data.getI(id, "deaths", 0),
                "&bPlaytime: &f" + Util.duration(ticks / 20),
                "&e ",
                plugin.getConfig().getString("sidebar-footer", "&7play.example.com"),
        };
        int score = lines.length;
        for (String line : lines) {
            obj.getScore(SECTION.serialize(Util.c(line))).setScore(score--);
        }
    }

    // ---- commands ----

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "rank": {
                if (args.length == 0) {
                    if (sender instanceof Player p) {
                        Util.msg(p, "&6Your rank: &f" + plugin.rankOf(p.getUniqueId()));
                    } else {
                        Util.msg(sender, "&cUsage: /rank <player> [rank]");
                    }
                    return true;
                }
                UUID target = lookup(args[0]);
                if (target == null) {
                    Util.msg(sender, "&cUnknown player.");
                    return true;
                }
                if (args.length == 1) {
                    Util.msg(sender, "&6" + plugin.data.nameOf(target) + "'s rank: &f" + plugin.rankOf(target));
                    return true;
                }
                if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !sender.hasPermission("donutcore.owner")) {
                    Util.msg(sender, "&cOnly owners can change ranks.");
                    return true;
                }
                String rank = args[1].toLowerCase(Locale.ROOT);
                if (!RANKS.contains(rank)) {
                    Util.msg(sender, "&cRanks: " + String.join(", ", RANKS));
                    return true;
                }
                plugin.data.setS(target, "rank", rank);
                plugin.data.save(false);
                Player online = Bukkit.getPlayer(target);
                if (online != null) {
                    apply(online);
                    Util.msg(online, "&6Your rank is now &f" + rank + "&6.");
                }
                Util.msg(sender, "&a" + plugin.data.nameOf(target) + " is now &f" + rank + "&a.");
                return true;
            }
            case "mute":
            case "unmute": {
                if (!sender.hasPermission("donutcore.staff")) {
                    Util.msg(sender, "&cYou do not have permission.");
                    return true;
                }
                if (args.length < 1) {
                    Util.msg(sender, "&cUsage: /" + label + " <player>");
                    return true;
                }
                UUID target = lookup(args[0]);
                if (target == null) {
                    Util.msg(sender, "&cUnknown player.");
                    return true;
                }
                boolean mute = cmd.getName().equalsIgnoreCase("mute");
                plugin.data.setI(target, "muted", mute ? 1 : 0);
                plugin.data.save(false);
                Util.msg(sender, "&a" + plugin.data.nameOf(target) + (mute ? " &amuted." : " &aunmuted."));
                Player online = Bukkit.getPlayer(target);
                if (online != null) Util.msg(online, mute ? "&cYou have been muted." : "&aYou have been unmuted.");
                return true;
            }
            default:
                return false;
        }
    }

    private UUID lookup(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        return plugin.data.findByName(name);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return Util.onlineNames(args[0]);
        if (args.length == 2 && cmd.getName().equalsIgnoreCase("rank")) return Util.filter(RANKS, args[1]);
        return List.of();
    }

    @SuppressWarnings("unused")
    private static Component unused() {
        return Component.empty();
    }
}
