package hu.donutcore;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Money + shards, and the /balance /pay /baltop /eco /shards commands. */
public final class Economy implements TabExecutor {
    private final DonutCore plugin;

    public Economy(DonutCore plugin) {
        this.plugin = plugin;
    }

    // ---- API ----

    private double startingBalance() {
        return plugin.getConfig().getDouble("economy.starting-balance", 1000.0);
    }

    public double balance(UUID id) {
        return plugin.data.getD(id, "money", startingBalance());
    }

    public void set(UUID id, double value) {
        plugin.data.setD(id, "money", Math.max(0, value));
    }

    public void add(UUID id, double value) {
        set(id, balance(id) + value);
    }

    public boolean take(UUID id, double value) {
        if (balance(id) < value) return false;
        set(id, balance(id) - value);
        return true;
    }

    public double shards(UUID id) {
        return plugin.data.getD(id, "shards", 0);
    }

    public void setShards(UUID id, double value) {
        plugin.data.setD(id, "shards", Math.max(0, value));
    }

    private UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        return plugin.data.findByName(name);
    }

    // ---- Commands ----

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "balance": {
                UUID target;
                boolean self = false;
                if (args.length > 0) {
                    target = resolve(args[0]);
                    if (target == null) {
                        Util.msg(sender, "&cUnknown player.");
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p.getUniqueId();
                    self = true;
                } else {
                    Util.msg(sender, "&cUsage: /balance <player>");
                    return true;
                }
                if (self) {
                    Util.msg(sender, "&6Your balance: &a" + Util.money(balance(target)));
                } else {
                    Util.msg(sender, "&6" + plugin.data.nameOf(target) + "'s balance: &a" + Util.money(balance(target)));
                }
                return true;
            }
            case "shards": {
                UUID target;
                if (args.length > 0) {
                    target = resolve(args[0]);
                    if (target == null) {
                        Util.msg(sender, "&cUnknown player.");
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p.getUniqueId();
                } else {
                    Util.msg(sender, "&cUsage: /shards <player>");
                    return true;
                }
                Util.msg(sender, "&d" + plugin.data.nameOf(target) + " has &f" + (long) shards(target) + " &dshards");
                return true;
            }
            case "pay": {
                if (!(sender instanceof Player p)) {
                    Util.msg(sender, "&cOnly players can pay.");
                    return true;
                }
                if (args.length < 2) {
                    Util.msg(sender, "&cUsage: /pay <player> <amount>");
                    return true;
                }
                UUID target = resolve(args[0]);
                if (target == null) {
                    Util.msg(sender, "&cUnknown player.");
                    return true;
                }
                if (target.equals(p.getUniqueId())) {
                    Util.msg(sender, "&cYou cannot pay yourself.");
                    return true;
                }
                double amount;
                try {
                    amount = Util.parseAmount(args[1]);
                } catch (NumberFormatException ex) {
                    Util.msg(sender, "&cInvalid amount. Examples: 500, 1.5k, 2m");
                    return true;
                }
                if (amount < 0.01) {
                    Util.msg(sender, "&cInvalid amount.");
                    return true;
                }
                if (!take(p.getUniqueId(), amount)) {
                    Util.msg(sender, "&cYou do not have enough money.");
                    return true;
                }
                add(target, amount);
                Util.msg(sender, "&aYou paid &6" + plugin.data.nameOf(target) + " &a" + Util.money(amount) + ".");
                Player online = Bukkit.getPlayer(target);
                if (online != null) {
                    Util.msg(online, "&6" + p.getName() + " &apaid you &6" + Util.money(amount) + "&a.");
                }
                return true;
            }
            case "baltop": {
                List<Map.Entry<UUID, Double>> top = plugin.data.topMoney(10, startingBalance());
                Util.msg(sender, "&6&lRichest players");
                int i = 1;
                for (Map.Entry<UUID, Double> e : top) {
                    Util.msg(sender, "&e#" + i++ + " &f" + plugin.data.nameOf(e.getKey()) + " &7- &a" + Util.money(e.getValue()));
                }
                return true;
            }
            case "eco": {
                if (!sender.hasPermission("donutcore.admin")) {
                    Util.msg(sender, "&cYou do not have permission.");
                    return true;
                }
                if (args.length < 3) {
                    Util.msg(sender, "&cUsage: /eco <give|take|set> <player> <amount> [money|shards]");
                    return true;
                }
                UUID target = resolve(args[1]);
                if (target == null) {
                    Util.msg(sender, "&cUnknown player.");
                    return true;
                }
                double amount;
                try {
                    amount = Util.parseAmount(args[2]);
                } catch (NumberFormatException ex) {
                    Util.msg(sender, "&cInvalid amount.");
                    return true;
                }
                boolean shards = args.length > 3 && args[3].equalsIgnoreCase("shards");
                double current = shards ? shards(target) : balance(target);
                double result;
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "give": result = current + amount; break;
                    case "take": result = current - amount; break;
                    case "set": result = amount; break;
                    default:
                        Util.msg(sender, "&cUsage: /eco <give|take|set> <player> <amount> [money|shards]");
                        return true;
                }
                if (shards) setShards(target, result); else set(target, result);
                Util.msg(sender, "&aDone. " + plugin.data.nameOf(target) + " now has &f"
                        + (shards ? (long) Math.max(0, result) + " shards" : Util.money(Math.max(0, result))));
                return true;
            }
            default:
                return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1 && (n.equals("balance") || n.equals("pay") || n.equals("shards"))) {
            return Util.onlineNames(args[0]);
        }
        if (n.equals("eco")) {
            if (args.length == 1) return Util.filter(List.of("give", "take", "set"), args[0]);
            if (args.length == 2) return Util.onlineNames(args[1]);
            if (args.length == 4) return Util.filter(List.of("money", "shards"), args[3]);
        }
        return List.of();
    }
}
