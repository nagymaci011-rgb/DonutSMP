package hu.donutcore;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class DonutCore extends JavaPlugin {
    public Data data;
    public Economy eco;
    public CombatManager combat;
    public AuctionHouse ah;
    public Teleports teleports;
    public RankManager ranks;
    public SellManager sell;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        data = new Data(this);
        eco = new Economy(this);
        combat = new CombatManager(this);
        sell = new SellManager(this);
        ah = new AuctionHouse(this);
        teleports = new Teleports(this);
        ranks = new RankManager(this);

        for (String c : new String[]{"balance", "pay", "baltop", "eco", "shards"}) bind(c, eco);
        bind("sell", sell);
        bind("ah", ah);
        for (String c : new String[]{"rtp", "spawn", "setspawn", "tpa", "tpaccept", "tpdeny"}) bind(c, teleports);
        for (String c : new String[]{"rank", "mute", "unmute"}) bind(c, ranks);

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(combat, this);
        pm.registerEvents(sell, this);
        pm.registerEvents(ah, this);
        pm.registerEvents(teleports, this);
        pm.registerEvents(ranks, this);

        combat.start();
        ah.start();
        ranks.start();

        // autosave every 5 minutes
        Bukkit.getScheduler().runTaskTimer(this, () -> data.save(false), 20L * 300, 20L * 300);
        getLogger().info("DonutCore enabled.");
    }

    @Override
    public void onDisable() {
        if (ranks != null) ranks.shutdown();
        if (ah != null) ah.shutdown();
        if (data != null) data.save(true);
    }

    private void bind(String name, TabExecutor exec) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command missing from plugin.yml: " + name);
            return;
        }
        cmd.setExecutor(exec);
        cmd.setTabCompleter(exec);
    }

    /** member / staff / admin / owner */
    public String rankOf(UUID id) {
        return data.getS(id, "rank", "member");
    }
}
