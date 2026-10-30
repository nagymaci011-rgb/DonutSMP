package hu.donutcore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Player market: /ah, /ah sell <price>, /ah claim. */
public final class AuctionHouse implements TabExecutor, Listener {
    private static final int PAGE_SIZE = 45;

    static final class AhHolder implements InventoryHolder {
        Inventory inv;
        int page;
        final List<Integer> ids = new ArrayList<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final class Listing {
        int id;
        UUID seller;
        String sellerName;
        double price;
        ItemStack item;
        long expires;
    }

    private final DonutCore plugin;
    private final File file;
    private final Object fileLock = new Object();
    private final Map<Integer, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> claims = new LinkedHashMap<>();
    private int nextId = 1;

    public AuctionHouse(DonutCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auction.yml");
        load();
    }

    // ---- persistence ----

    private void load() {
        YamlConfiguration in = YamlConfiguration.loadConfiguration(file);
        nextId = in.getInt("next-id", 1);
        ConfigurationSection sec = in.getConfigurationSection("listings");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                ItemStack item = s.getItemStack("item");
                String seller = s.getString("seller");
                if (item == null || seller == null) continue;
                try {
                    Listing l = new Listing();
                    l.id = Integer.parseInt(key);
                    l.seller = UUID.fromString(seller);
                    l.sellerName = s.getString("seller-name", "?");
                    l.price = s.getDouble("price");
                    l.item = item;
                    l.expires = s.getLong("expires");
                    listings.put(l.id, l);
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entry
                }
            }
        }
        ConfigurationSection cs = in.getConfigurationSection("claims");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                try {
                    List<ItemStack> list = new ArrayList<>();
                    for (Object o : cs.getList(key, new ArrayList<>())) {
                        if (o instanceof ItemStack is) list.add(is);
                    }
                    claims.put(UUID.fromString(key), list);
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entry
                }
            }
        }
    }

    private void save(boolean sync) {
        YamlConfiguration out = new YamlConfiguration();
        out.set("next-id", nextId);
        for (Listing l : listings.values()) {
            String b = "listings." + l.id + ".";
            out.set(b + "seller", l.seller.toString());
            out.set(b + "seller-name", l.sellerName);
            out.set(b + "price", l.price);
            out.set(b + "item", l.item);
            out.set(b + "expires", l.expires);
        }
        for (Map.Entry<UUID, List<ItemStack>> e : claims.entrySet()) {
            if (!e.getValue().isEmpty()) out.set("claims." + e.getKey(), e.getValue());
        }
        final String text = out.saveToString();
        Runnable write = () -> {
            synchronized (fileLock) {
                try {
                    plugin.getDataFolder().mkdirs();
                    Files.writeString(file.toPath(), text);
                } catch (IOException ex) {
                    plugin.getLogger().warning("Could not save auction.yml: " + ex.getMessage());
                }
            }
        };
        if (sync) write.run();
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
    }

    public void shutdown() {
        save(true);
    }

    // ---- expiry ----

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20L * 60, 20L * 60);
    }

    private void expire() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Listing> it = listings.values().iterator();
        while (it.hasNext()) {
            Listing l = it.next();
            if (l.expires <= now) {
                it.remove();
                claims.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item);
                changed = true;
                Player seller = Bukkit.getPlayer(l.seller);
                if (seller != null) {
                    Util.msg(seller, "&6One of your auction listings expired. Use &e/ah claim &6to get the item back.");
                }
            }
        }
        if (changed) save(false);
    }

    public void notifyClaims(Player p) {
        List<ItemStack> list = claims.get(p.getUniqueId());
        if (list != null && !list.isEmpty()) {
            Util.msg(p, "&6You have &e" + list.size() + " &6auction item(s) waiting. Use &e/ah claim&6.");
        }
    }

    // ---- commands ----

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            Util.msg(sender, "&cOnly players can use the auction house.");
            return true;
        }
        if (args.length == 0) {
            open(p, 0);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell": {
                if (args.length < 2) {
                    Util.msg(p, "&cUsage: /ah sell <price>");
                    return true;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR) {
                    Util.msg(p, "&cHold the item you want to sell.");
                    return true;
                }
                double price;
                try {
                    price = Util.parseAmount(args[1]);
                } catch (NumberFormatException ex) {
                    Util.msg(p, "&cInvalid price. Examples: 500, 1.5k, 2m");
                    return true;
                }
                double max = plugin.getConfig().getDouble("auction.max-price", 1_000_000_000_000.0);
                if (price < 1 || price > max) {
                    Util.msg(p, "&cPrice must be between $1 and " + Util.money(max) + ".");
                    return true;
                }
                int maxListings = plugin.getConfig().getInt("auction.max-listings", 5);
                int mine = 0;
                for (Listing l : listings.values()) {
                    if (l.seller.equals(p.getUniqueId())) mine++;
                }
                if (mine >= maxListings) {
                    Util.msg(p, "&cYou can only have " + maxListings + " active listings.");
                    return true;
                }
                Listing l = new Listing();
                l.id = nextId++;
                l.seller = p.getUniqueId();
                l.sellerName = p.getName();
                l.price = price;
                l.item = hand.clone();
                l.expires = System.currentTimeMillis()
                        + plugin.getConfig().getLong("auction.duration-hours", 48) * 3_600_000L;
                listings.put(l.id, l);
                p.getInventory().setItemInMainHand(null);
                save(false);
                Util.msg(p, "&aListed &f" + l.item.getAmount() + "x " + l.item.getType().name().toLowerCase().replace('_', ' ')
                        + " &afor &6" + Util.money(price) + "&a.");
                return true;
            }
            case "claim": {
                List<ItemStack> list = claims.get(p.getUniqueId());
                if (list == null || list.isEmpty()) {
                    Util.msg(p, "&cNothing to claim.");
                    return true;
                }
                int n = list.size();
                for (ItemStack it : list) Util.give(p, it);
                list.clear();
                save(false);
                Util.msg(p, "&aClaimed &f" + n + " &aitem(s).");
                return true;
            }
            default:
                Util.msg(p, "&cUsage: /ah, /ah sell <price>, /ah claim");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return Util.filter(List.of("sell", "claim"), args[0]);
        return List.of();
    }

    // ---- GUI ----

    public void open(Player p, int page) {
        List<Listing> all = new ArrayList<>(listings.values());
        Collections.reverse(all); // newest first
        int maxPage = Math.max(0, (all.size() - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage));

        AhHolder holder = new AhHolder();
        holder.page = page;
        Inventory inv = Bukkit.createInventory(holder, 54, Util.c("&6Auction House &7(" + (page + 1) + "/" + (maxPage + 1) + ")"));
        holder.inv = inv;

        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = page * PAGE_SIZE + i;
            if (idx >= all.size()) break;
            Listing l = all.get(idx);
            holder.ids.add(l.id);
            inv.setItem(i, display(l, p));
        }

        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, "&r");
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);
        if (page > 0) inv.setItem(45, named(Material.ARROW, "&ePrevious page"));
        if (page < maxPage) inv.setItem(53, named(Material.ARROW, "&eNext page"));
        inv.setItem(49, named(Material.PAPER, "&6Page " + (page + 1) + " &7- &f" + all.size() + " listings"));
        p.openInventory(inv);
    }

    private ItemStack named(Material m, String name) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> meta.displayName(Util.c(name)));
        return it;
    }

    private ItemStack display(Listing l, Player viewer) {
        ItemStack it = l.item.clone();
        boolean own = l.seller.equals(viewer.getUniqueId());
        it.editMeta(meta -> {
            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Util.c("&8-----------------"));
            lore.add(Util.c("&7Price: &a" + Util.money(l.price)));
            lore.add(Util.c("&7Seller: &f" + l.sellerName));
            lore.add(Util.c("&7Expires in: &f" + Util.duration((l.expires - System.currentTimeMillis()) / 1000)));
            lore.add(Util.c(own ? "&cClick to cancel your listing" : "&eClick to buy"));
            meta.lore(lore);
        });
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof AhHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getInventory())) return;

        int slot = e.getRawSlot();
        if (slot == 45 && h.page > 0) {
            open(p, h.page - 1);
        } else if (slot == 53) {
            open(p, h.page + 1); // clamped in open()
        } else if (slot >= 0 && slot < PAGE_SIZE && slot < h.ids.size()) {
            buyOrCancel(p, h.ids.get(slot), h.page);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof AhHolder) e.setCancelled(true);
    }

    private void buyOrCancel(Player p, int id, int page) {
        Listing l = listings.get(id);
        if (l == null) {
            Util.msg(p, "&cThat listing is no longer available.");
            reopen(p, page);
            return;
        }
        if (l.seller.equals(p.getUniqueId())) {
            listings.remove(id);
            Util.give(p, l.item);
            save(false);
            Util.msg(p, "&aListing cancelled, item returned.");
            reopen(p, page);
            return;
        }
        if (!plugin.eco.take(p.getUniqueId(), l.price)) {
            Util.msg(p, "&cYou need " + Util.money(l.price) + " to buy this.");
            return;
        }
        double tax = plugin.getConfig().getDouble("auction.tax-percent", 0) / 100.0;
        plugin.eco.add(l.seller, l.price * (1 - tax));
        listings.remove(id);
        Util.give(p, l.item);
        save(false);
        Util.msg(p, "&aBought &f" + l.item.getAmount() + "x " + l.item.getType().name().toLowerCase().replace('_', ' ')
                + " &afor &6" + Util.money(l.price) + "&a.");
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            Util.msg(seller, "&6" + p.getName() + " &abought your item for &6" + Util.money(l.price * (1 - tax)) + "&a.");
        }
        reopen(p, page);
    }

    private void reopen(Player p, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) open(p, page);
        });
    }
}
