package hu.donutcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Util {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String[] SUFFIX = {"", "K", "M", "B", "T"};

    private Util() {
    }

    /** Colored component from an &-coded string, italics off (good for item names and lore). */
    public static Component c(String text) {
        return LEGACY.deserialize(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static void msg(CommandSender to, String text) {
        to.sendMessage(c(text));
    }

    /** 1500 -> $1.5K */
    public static String money(double value) {
        int i = 0;
        double x = value;
        while (Math.abs(x) >= 1000 && i < SUFFIX.length - 1) {
            x /= 1000;
            i++;
        }
        String s = String.format(Locale.US, "%.2f", x);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return "$" + s + SUFFIX[i];
    }

    /** Accepts 1000, 1,000, 1k, 2.5m, 3b, 1t. Throws NumberFormatException on bad input. */
    public static double parseAmount(String input) {
        String s = input.toLowerCase(Locale.ROOT).replace(",", "").replace("$", "").trim();
        double mult = 1;
        if (!s.isEmpty()) {
            switch (s.charAt(s.length() - 1)) {
                case 'k': mult = 1e3; break;
                case 'm': mult = 1e6; break;
                case 'b': mult = 1e9; break;
                case 't': mult = 1e12; break;
                default: break;
            }
            if (mult != 1) {
                s = s.substring(0, s.length() - 1);
            }
        }
        if (!s.matches("\\d+(\\.\\d+)?")) {
            throw new NumberFormatException("invalid amount");
        }
        double v = Double.parseDouble(s) * mult;
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) {
            throw new NumberFormatException("invalid amount");
        }
        return Math.floor(v * 100) / 100;
    }

    public static String duration(long seconds) {
        if (seconds < 0) seconds = 0;
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    /** Give an item; whatever does not fit is dropped at the player's feet. */
    public static void give(Player p, ItemStack item) {
        for (ItemStack left : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    public static List<String> onlineNames(String prefix) {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(p.getName());
            }
        }
        return out;
    }

    public static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(o);
            }
        }
        return out;
    }
}
