package hu.donutcore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** /sell opens a chest GUI; closing it sells everything that has a price. */
public final class SellManager implements TabExecutor, Listener {
    static final class SellHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final DonutCore plugin;

    public SellManager(DonutCore plugin) {
        this.plugin = plugin;
    }

    private double price(ItemStack item) {
        if (item == null || item.getType().isAir() || item.hasItemMeta()) return 0;
        return plugin.getConfig().getDouble("sell-prices." + item.getType().name(), 0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            Util.msg(sender, "&cOnly players can sell.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("hand")) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            double each = price(hand);
            if (each <= 0) {
                Util.msg(p, "&cThat item cannot be sold.");
                return true;
            }
            double total = each * hand.getAmount();
            int amount = hand.getAmount();
            p.getInventory().setItemInMainHand(null);
            plugin.eco.add(p.getUniqueId(), total);
            Util.msg(p, "&aSold &f" + amount + "x " + hand.getType().name().toLowerCase().replace('_', ' ')
                    + " &afor &6" + Util.money(total) + "&a.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("worth")) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            double each = price(hand);
            if (each <= 0) {
                Util.msg(p, "&cThat item cannot be sold.");
            } else {
                Util.msg(p, "&6Worth: &a" + Util.money(each) + " each &7(" + Util.money(each * hand.getAmount()) + " for the stack)");
            }
            return true;
        }
        SellHolder holder = new SellHolder();
        Inventory inv = Bukkit.createInventory(holder, 36, Util.c("&aSell &7- put items in, then close"));
        holder.inv = inv;
        p.openInventory(inv);
        return true;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof SellHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        double total = 0;
        int sold = 0;
        for (ItemStack it : e.getInventory().getContents()) {
            if (it == null || it.getType().isAir()) continue;
            double each = price(it);
            if (each > 0) {
                total += each * it.getAmount();
                sold += it.getAmount();
            } else {
                Util.give(p, it);
            }
        }
        e.getInventory().clear();
        if (sold > 0) {
            plugin.eco.add(p.getUniqueId(), total);
            Util.msg(p, "&aSold &f" + sold + " &aitems for &6" + Util.money(total) + "&a.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return Util.filter(List.of("hand", "worth"), args[0]);
        return List.of();
    }

    @SuppressWarnings("unused")
    private static boolean isOre(Material m) {
        return m != null;
    }
}
