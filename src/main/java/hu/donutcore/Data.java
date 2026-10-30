package hu.donutcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Simple YAML-backed player data store (money, shards, kills, deaths, playtime, rank, spawn). */
public final class Data {
    private final DonutCore plugin;
    private final File file;
    private final YamlConfiguration yml;
    private final Object fileLock = new Object();

    public Data(DonutCore plugin) {
        this.plugin = plugin;
        plugin.getDataFolder().mkdirs();
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.yml = YamlConfiguration.loadConfiguration(file);
    }

    private String path(UUID id, String key) {
        return "players." + id + "." + key;
    }

    public double getD(UUID id, String key, double def) {
        return yml.getDouble(path(id, key), def);
    }

    public void setD(UUID id, String key, double value) {
        yml.set(path(id, key), value);
    }

    public int getI(UUID id, String key, int def) {
        return yml.getInt(path(id, key), def);
    }

    public void setI(UUID id, String key, int value) {
        yml.set(path(id, key), value);
    }

    public long getL(UUID id, String key, long def) {
        return yml.getLong(path(id, key), def);
    }

    public void setL(UUID id, String key, long value) {
        yml.set(path(id, key), value);
    }

    public String getS(UUID id, String key, String def) {
        return yml.getString(path(id, key), def);
    }

    public void setS(UUID id, String key, String value) {
        yml.set(path(id, key), value);
    }

    public UUID findByName(String name) {
        ConfigurationSection sec = yml.getConfigurationSection("players");
        if (sec == null) return null;
        for (String key : sec.getKeys(false)) {
            String n = sec.getString(key + ".name");
            if (n != null && n.equalsIgnoreCase(name)) {
                try {
                    return UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {
                    // skip malformed key
                }
            }
        }
        return null;
    }

    public List<Map.Entry<UUID, Double>> topMoney(int limit, double defaultBalance) {
        List<Map.Entry<UUID, Double>> all = new ArrayList<>();
        ConfigurationSection sec = yml.getConfigurationSection("players");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    all.add(Map.entry(id, sec.getDouble(key + ".money", defaultBalance)));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed key
                }
            }
        }
        all.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    public Location getSpawn() {
        return yml.getLocation("spawn");
    }

    public void setSpawn(Location loc) {
        yml.set("spawn", loc);
    }

    public String nameOf(UUID id) {
        String n = getS(id, "name", null);
        if (n != null) return n;
        String off = Bukkit.getOfflinePlayer(id).getName();
        return off != null ? off : id.toString().substring(0, 8);
    }

    public void save(boolean sync) {
        final String text = yml.saveToString();
        Runnable write = () -> {
            synchronized (fileLock) {
                try {
                    Files.writeString(file.toPath(), text);
                } catch (IOException ex) {
                    plugin.getLogger().warning("Could not save data.yml: " + ex.getMessage());
                }
            }
        };
        if (sync) {
            write.run();
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        }
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
