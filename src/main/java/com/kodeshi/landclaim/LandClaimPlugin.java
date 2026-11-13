package com.kodeshi.landclaim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LandClaimPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    public ClaimManager claims;
    public BorderManager borders;
    public Gui gui;
    public boolean floodgate;
    private VaultHook vault;
    private NamespacedKey itemKey;

    public static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    public static String tr(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public void msg(CommandSender to, String m) {
        to.sendMessage(tr(getConfig().getString("prefix", "&8[&aClaims&8] &7") + m));
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "menu_item");
        floodgate = getServer().getPluginManager().getPlugin("floodgate") != null;
        claims = new ClaimManager(this);
        claims.load();
        borders = new BorderManager(this);
        gui = new Gui(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new ProtectionListener(this), this);
        pm.registerEvents(gui, this);
        pm.registerEvents(this, this);

        for (String name : List.of("confirm", "claimcancel", "claimmenu", "deleteclaim", "ownerchange", "claimreload")) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(this);
                getCommand(name).setTabCompleter(this);
            }
        }
        claims.startTasks();
        borders.start();
        getLogger().info("LandClaim enabled. Bedrock forms: " + (floodgate ? "on (Floodgate)" : "off") + ", economy: " + (eco() != null ? "on" : "off"));
    }

    @Override
    public void onDisable() {
        if (borders != null) borders.stop();
        if (claims != null) claims.flush(true);
    }

    // ---------- economy (Vault is optional) ----------

    public VaultHook eco() {
        if (vault == null && getServer().getPluginManager().getPlugin("Vault") != null) {
            vault = VaultHook.create(getServer());
        }
        return vault;
    }

    public boolean charge(Player p, double amt) {
        if (amt <= 0) return true;
        VaultHook e = eco();
        if (e == null) {
            msg(p, "&cNo economy found (install Vault + an economy plugin).");
            return false;
        }
        if (!e.has(p, amt)) {
            msg(p, "&cYou need &e" + e.format(amt) + "&c.");
            return false;
        }
        if (!e.withdraw(p, amt)) {
            msg(p, "&cPayment failed.");
            return false;
        }
        return true;
    }

    public void pay(UUID to, double amt) {
        VaultHook e = eco();
        if (e == null || amt <= 0) return;
        e.deposit(Bukkit.getOfflinePlayer(to), amt);
        Player o = Bukkit.getPlayer(to);
        if (o != null) msg(o, "&aYou received &e" + e.format(amt) + "&a.");
    }

    public String money(double a) {
        VaultHook e = eco();
        return e == null ? String.valueOf(a) : e.format(a);
    }

    // ---------- menu item ----------

    public ItemStack menuItem() {
        Material m = Material.matchMaterial(getConfig().getString("menu-item.material", "NETHER_STAR"));
        ItemStack it = new ItemStack(m == null ? Material.NETHER_STAR : m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(getConfig().getString("menu-item.name", "&aClaim Menu")));
        meta.lore(List.of(c("&7Right-click to open")));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public boolean isMenuItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    public void giveMenuItem(Player p) {
        if (!getConfig().getBoolean("menu-item.enabled", true)) return;
        PlayerInventory inv = p.getInventory();
        for (ItemStack s : inv.getContents()) {
            if (isMenuItem(s)) return;
        }
        int slot = Math.max(0, Math.min(8, getConfig().getInt("menu-item.slot", 8)));
        ItemStack cur = inv.getItem(slot);
        if (cur == null || cur.getType().isAir()) inv.setItem(slot, menuItem());
        else inv.addItem(menuItem());
    }

    // ---------- events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        claims.touch(e.getPlayer().getUniqueId());
        giveMenuItem(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> giveMenuItem(p));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isMenuItem);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isMenuItem(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMenuItem(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> gui.main(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        claims.touch(e.getPlayer().getUniqueId());
        borders.remove(e.getPlayer());
        gui.forget(e.getPlayer());
    }

    // ---------- commands ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase();
        boolean admin = s.hasPermission("landclaim.admin");
        switch (name) {
            case "confirm" -> {
                if (s instanceof Player p) borders.confirm(p);
            }
            case "claimcancel" -> {
                if (s instanceof Player p) borders.cancel(p);
            }
            case "claimmenu" -> {
                if (s instanceof Player p) {
                    giveMenuItem(p);
                    gui.main(p);
                }
            }
            case "claimreload" -> {
                if (!admin) {
                    msg(s, "&cNo permission.");
                    return true;
                }
                reloadConfig();
                msg(s, "&aConfig reloaded.");
            }
            case "deleteclaim" -> {
                if (!admin) {
                    msg(s, "&cNo permission.");
                    return true;
                }
                if (a.length == 0) {
                    msg(s, "&7Usage: &e/deleteclaim <landname>");
                    return true;
                }
                List<Claim> found = claims.findByName(String.join(" ", a));
                if (found.isEmpty()) {
                    msg(s, "&cNo claim with that name.");
                } else if (found.size() > 1) {
                    msg(s, "&eMore than one claim has that name. Use &f/deleteclaim <landname>@<owner>&e:");
                    for (Claim c : found) msg(s, "&7- &f" + c.name + "@" + claims.nameOf(c.owner));
                } else {
                    Claim c = found.get(0);
                    claims.delete(c);
                    msg(s, "&aDeleted claim &f" + c.name + " &7(owner " + claims.nameOf(c.owner) + ")&a.");
                    Player o = Bukkit.getPlayer(c.owner);
                    if (o != null) msg(o, "&cYour claim &f" + c.name + " &cwas deleted by an admin.");
                }
            }
            case "ownerchange" -> {
                if (!admin) {
                    msg(s, "&cNo permission.");
                    return true;
                }
                if (a.length == 0) {
                    msg(s, "&7Usage: &e/ownerchange <username> [landname]  &7(stand inside the claim, or give its name)");
                    return true;
                }
                OfflinePlayer target = claims.find(a[0]);
                if (target == null) {
                    msg(s, "&cPlayer not found. They must have joined the server before.");
                    return true;
                }
                Claim c = null;
                if (a.length > 1) {
                    List<Claim> found = claims.findByName(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
                    if (found.size() == 1) c = found.get(0);
                    else {
                        msg(s, found.isEmpty() ? "&cNo claim with that name." : "&cMore than one claim has that name. Use <landname>@<owner>.");
                        return true;
                    }
                } else if (s instanceof Player p) {
                    c = claims.at(p.getLocation());
                }
                if (c == null) {
                    msg(s, "&cStand inside the claim, or give the claim name.");
                    return true;
                }
                String old = claims.nameOf(c.owner);
                claims.transfer(c, target.getUniqueId());
                msg(s, "&aClaim &f" + c.name + " &ais now owned by &f" + claims.nameOf(target.getUniqueId()) + " &7(was " + old + ")&a.");
                Player np = target.getPlayer();
                if (np != null) msg(np, "&aYou are now the owner of the claim &f" + c.name + "&a.");
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase();
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("landclaim.admin")) return out;
        String typed = String.join(" ", a).toLowerCase();
        if (name.equals("deleteclaim")) {
            for (Claim c : claims.all()) {
                if (c.name.toLowerCase().startsWith(typed)) out.add(c.name);
            }
        } else if (name.equals("ownerchange") && a.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(a[0].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
