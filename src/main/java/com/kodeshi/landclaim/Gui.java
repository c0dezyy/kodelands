package com.kodeshi.landclaim;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Bedrock players get Floodgate forms (with icons); Java players get a chest menu. */
public final class Gui implements Listener {
    public record Btn(String label, String icon, Material mat, Runnable run) {
    }

    static final class Holder implements InventoryHolder {
        final Map<Integer, Runnable> acts = new HashMap<>();
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final LandClaimPlugin pl;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    Gui(LandClaimPlugin pl) {
        this.pl = pl;
    }

    public void forget(Player p) {
        pending.remove(p.getUniqueId());
    }

    private boolean bedrock(Player p) {
        return pl.floodgate && Bedrock.is(p);
    }

    private static Btn b(String label, String icon, Material m, Runnable r) {
        return new Btn(label, icon, m, r);
    }

    private Btn back(Runnable r) {
        return b("&7Back", "back", Material.ARROW, r);
    }

    // ---------- core renderers ----------

    public void menu(Player p, String title, String content, List<Btn> btns) {
        if (bedrock(p)) {
            Bedrock.menu(pl, p, title, content, btns, pl.getConfig().getConfigurationSection("icons"));
            return;
        }
        List<Btn> items = new ArrayList<>();
        Btn back = null;
        for (Btn x : btns) {
            if ("back".equals(x.icon())) back = x;
            else items.add(x);
        }
        boolean fancy = items.size() <= 28;
        int size;
        int[] slots;
        if (fancy) {
            int rows = Math.max(1, (items.size() + 6) / 7);
            size = (rows + 2) * 9;
            slots = new int[items.size()];
            for (int i = 0; i < slots.length; i++) {
                int row = i / 7;
                int inRow = Math.min(7, items.size() - row * 7);
                slots[i] = (row + 1) * 9 + 1 + (7 - inRow) / 2 + (i % 7);
            }
        } else {
            size = 54;
            int cap = back != null ? 53 : 54;
            if (items.size() > cap) items = items.subList(0, cap);
            slots = new int[items.size()];
            for (int i = 0; i < slots.length; i++) slots[i] = i;
        }
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, size, LandClaimPlugin.c(title));
        h.inv = inv;
        if (fancy) {
            ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta pm = pane.getItemMeta();
            pm.displayName(Component.text(" "));
            pane.setItemMeta(pm);
            for (int i = 0; i < size; i++) inv.setItem(i, pane);
        }
        for (int i = 0; i < items.size(); i++) {
            inv.setItem(slots[i], icon(items.get(i)));
            h.acts.put(slots[i], items.get(i).run());
        }
        if (back != null) {
            inv.setItem(size - 1, icon(back));
            h.acts.put(size - 1, back.run());
        }
        p.openInventory(inv);
    }

    private static ItemStack icon(Btn x) {
        ItemStack it = new ItemStack(x.mat());
        ItemMeta m = it.getItemMeta();
        m.displayName(LandClaimPlugin.c(x.label()));
        it.setItemMeta(m);
        return it;
    }

    public void input(Player p, String title, String label, String hint, Consumer<String> cb) {
        if (bedrock(p)) {
            Bedrock.input(pl, p, title, label, hint, cb);
            return;
        }
        p.closeInventory();
        pending.put(p.getUniqueId(), cb);
        pl.msg(p, label + " &7(type it in chat, or type &ecancel&7)");
    }

    public void confirm(Player p, String text, Runnable yes, Runnable no) {
        if (!bedrock(p)) pl.msg(p, text);
        menu(p, "&6Confirm", text, List.of(
                b("&aYes", "yes", Material.LIME_CONCRETE, yes),
                b("&cNo", "no", Material.RED_CONCRETE, no)));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Runnable r = h.acts.get(e.getRawSlot());
        if (r != null) Bukkit.getScheduler().runTask(pl, r);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Consumer<String> cb = pending.remove(e.getPlayer().getUniqueId());
        if (cb == null) return;
        e.setCancelled(true);
        String s = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(pl, () -> {
            if (s.equalsIgnoreCase("cancel")) pl.msg(p, "&7Cancelled.");
            else cb.accept(s);
        });
    }

    // ---------- menus ----------

    private boolean marketOn() {
        FileConfiguration f = pl.getConfig();
        return pl.eco() != null && (f.getBoolean("rent.enabled") || f.getBoolean("sell.enabled"));
    }

    public void main(Player p) {
        List<Btn> l = new ArrayList<>();
        BorderManager.Sess s = pl.borders.peek(p);
        if (s != null && s.mode != BorderManager.Mode.NONE) {
            String what = s.mode == BorderManager.Mode.NEW ? "Claim" : "Expansion";
            l.add(b("&aConfirm " + what, "yes", Material.LIME_CONCRETE, () -> {
                p.closeInventory();
                pl.borders.confirm(p);
            }));
            l.add(b("&cCancel", "no", Material.RED_CONCRETE, () -> {
                p.closeInventory();
                pl.borders.cancel(p);
            }));
            menu(p, "&2Confirm this spot?", "Happy with the green border?", l);
            return;
        }
        l.add(b("&aAdd Claim", "claim", Material.GRASS_BLOCK, () -> pl.borders.startNew(p)));
        l.add(b("&eMy Claims", "list", Material.BOOK, () -> pickClaim(p, "&2My Claims", c -> claimMenu(p, c))));
        l.add(b("&bTrust Friend", "trust", Material.PLAYER_HEAD,
                () -> pickClaim(p, "&bTrust on which claim?", c -> askTrust(p, c))));
        if (marketOn()) l.add(b("&6Claim Market", "market", Material.EMERALD, () -> market(p)));
        menu(p, "&2Land Claim", "Select an action:", l);
    }

    private void pickClaim(Player p, String title, Consumer<Claim> next) {
        List<Claim> cs = pl.claims.managed(p.getUniqueId());
        if (cs.isEmpty()) {
            pl.msg(p, "&7You have no claims yet. Open the menu and pick &aAdd Claim&7.");
            return;
        }
        List<Btn> l = new ArrayList<>();
        for (Claim c : cs) {
            l.add(b("&f" + c.name + " &7(" + c.chunks.size() + ")", "claim", Material.GRASS_BLOCK, () -> next.accept(c)));
        }
        l.add(back(() -> main(p)));
        menu(p, title, "Select a claim:", l);
    }

    public void claimMenu(Player p, Claim c) {
        if (!pl.claims.alive(c) || !c.manages(p.getUniqueId())) return;
        FileConfiguration f = pl.getConfig();
        boolean owner = c.owner.equals(p.getUniqueId());
        List<Btn> l = new ArrayList<>();
        if (f.getBoolean("teleport.enabled", true)) {
            l.add(b("&bTeleport", "teleport", Material.ENDER_PEARL, () -> pl.claims.teleport(p, c)));
        }
        l.add(b("&aExpand Claim", "expand", Material.OAK_FENCE, () -> pl.borders.startExpand(p, c)));
        l.add(b("&eShow / Hide Border", "border", Material.LIME_STAINED_GLASS, () -> {
            pl.borders.toggleOwn(p);
            claimMenu(p, c);
        }));
        l.add(b("&dTrusted Players", "trust", Material.PLAYER_HEAD, () -> trustMenu(p, c)));
        l.add(b("&cBan / Kick", "ban", Material.BARRIER, () -> banMenu(p, c)));
        if (owner && pl.eco() != null && (f.getBoolean("rent.enabled") || f.getBoolean("sell.enabled"))) {
            l.add(b("&6Rent & Sell", "market", Material.GOLD_INGOT, () -> tradeMenu(p, c)));
        }
        l.add(b("&7Claim Info", "info", Material.PAPER, () -> info(p, c)));
        if (owner) l.add(b("&fSettings", "rename", Material.COMPARATOR, () -> settingsMenu(p, c)));
        l.add(back(() -> pickClaim(p, "&2My Claims", x -> claimMenu(p, x))));
        menu(p, "&2" + c.name, "Claim: " + c.name, l);
    }

    private void tradeMenu(Player p, Claim c) {
        FileConfiguration f = pl.getConfig();
        List<Btn> l = new ArrayList<>();
        if (f.getBoolean("rent.enabled")) l.add(b("&6Rent Out", "rent", Material.GOLD_INGOT, () -> rentMenu(p, c)));
        if (f.getBoolean("sell.enabled")) l.add(b("&6Sell Claim", "sell", Material.EMERALD, () -> sellMenu(p, c)));
        l.add(back(() -> claimMenu(p, c)));
        menu(p, "&6Rent & Sell - " + c.name, "Choose an option:", l);
    }

    private void settingsMenu(Player p, Claim c) {
        List<Btn> l = new ArrayList<>();
        l.add(b("&fRename Claim", "rename", Material.NAME_TAG, () -> input(p, "&2Rename", "&aEnter the new claim name", c.name, s -> {
            pl.claims.rename(p, c, s);
            claimMenu(p, c);
        })));
        l.add(b("&4Delete Claim", "delete", Material.LAVA_BUCKET, () -> confirm(p,
                "&cDelete claim &f" + c.name + "&c? This can't be undone.",
                () -> {
                    pl.claims.delete(c);
                    pl.msg(p, "&7Claim deleted.");
                },
                () -> settingsMenu(p, c))));
        l.add(back(() -> claimMenu(p, c)));
        menu(p, "&fSettings - " + c.name, "Claim settings:", l);
    }

    // ----- trust -----

    private void trustMenu(Player p, Claim c) {
        List<Btn> l = new ArrayList<>();
        l.add(b("&aAdd Trusted Player", "trust_add", Material.LIME_DYE, () -> askTrust(p, c)));
        for (UUID u : c.trusted.keySet()) {
            l.add(b("&f" + pl.claims.nameOf(u) + (c.coOwners.contains(u) ? " &6[Owner]" : ""), "player",
                    Material.PLAYER_HEAD, () -> permMenu(p, c, u)));
        }
        l.add(back(() -> claimMenu(p, c)));
        menu(p, "&dTrusted - " + c.name, "Manage trusted players:", l);
    }

    private void askTrust(Player p, Claim c) {
        input(p, "&dTrust player", "&aEnter the name of the player to trust on " + c.name, "PlayerName", name -> {
            OfflinePlayer t = pl.claims.find(name);
            if (t == null) {
                pl.msg(p, "&cPlayer not found. They must have joined the server before.");
                return;
            }
            if (c.manages(t.getUniqueId())) {
                pl.msg(p, "&cThat player already manages this claim.");
                return;
            }
            c.trusted.put(t.getUniqueId(), pl.claims.defaultPerms());
            pl.claims.markDirty();
            pl.msg(p, "&a" + name + " is now trusted on &f" + c.name + "&a.");
            trustMenu(p, c);
        });
    }

    private void permMenu(Player p, Claim c, UUID t) {
        Integer cur = c.trusted.get(t);
        if (cur == null) {
            trustMenu(p, c);
            return;
        }
        List<Btn> l = new ArrayList<>();
        l.add(toggle(p, c, t, cur, "Place Blocks", Claim.BUILD, Material.GRASS_BLOCK));
        l.add(toggle(p, c, t, cur, "Break Blocks", Claim.BREAK, Material.IRON_PICKAXE));
        l.add(toggle(p, c, t, cur, "Use Doors", Claim.DOORS, Material.OAK_DOOR));
        l.add(toggle(p, c, t, cur, "Open Chests", Claim.CHESTS, Material.CHEST));
        if (c.owner.equals(p.getUniqueId())) {
            boolean co = c.coOwners.contains(t);
            l.add(b("&6Owner Role: " + (co ? "&aON" : "&cOFF"), "owner", Material.NETHER_STAR, () -> {
                if (!c.coOwners.remove(t)) c.coOwners.add(t);
                pl.claims.markDirty();
                permMenu(p, c, t);
            }));
        }
        l.add(b("&cRemove Trusted", "delete", Material.BARRIER, () -> {
            c.trusted.remove(t);
            c.coOwners.remove(t);
            pl.claims.markDirty();
            trustMenu(p, c);
        }));
        l.add(back(() -> trustMenu(p, c)));
        String n = pl.claims.nameOf(t);
        menu(p, "&d" + n, "Permissions for " + n, l);
    }

    private Btn toggle(Player p, Claim c, UUID t, int cur, String label, int bit, Material m) {
        return b("&f" + label + ": " + ((cur & bit) != 0 ? "&aON" : "&cOFF"), "info", m, () -> {
            c.trusted.merge(t, bit, (a, x) -> a ^ x);
            pl.claims.markDirty();
            permMenu(p, c, t);
        });
    }

    // ----- ban / kick -----

    private void banMenu(Player p, Claim c) {
        List<Btn> l = new ArrayList<>();
        l.add(b("&cBan Player", "ban", Material.BARRIER, () -> input(p, "&cBan player", "&aEnter the name of the player to ban", "PlayerName", name -> {
            OfflinePlayer t = pl.claims.find(name);
            if (t == null) {
                pl.msg(p, "&cPlayer not found.");
                return;
            }
            if (c.manages(t.getUniqueId())) {
                pl.msg(p, "&cYou can't ban an owner.");
                return;
            }
            c.banned.add(t.getUniqueId());
            c.trusted.remove(t.getUniqueId());
            pl.claims.markDirty();
            Player on = t.getPlayer();
            if (on != null && pl.claims.at(on.getLocation()) == c) pl.claims.eject(c, on);
            pl.msg(p, "&c" + name + " is banned from &f" + c.name + "&c.");
            banMenu(p, c);
        })));
        l.add(b("&6Kick Player", "ban", Material.IRON_DOOR, () -> kickMenu(p, c)));
        for (UUID u : c.banned) {
            l.add(b("&aUnban " + pl.claims.nameOf(u), "yes", Material.LIME_DYE, () -> {
                c.banned.remove(u);
                pl.claims.markDirty();
                banMenu(p, c);
            }));
        }
        l.add(back(() -> claimMenu(p, c)));
        menu(p, "&cBan / Kick - " + c.name, "Choose an action:", l);
    }

    private void kickMenu(Player p, Claim c) {
        List<Btn> l = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (pl.claims.at(o.getLocation()) == c && !c.manages(o.getUniqueId())) {
                l.add(b("&f" + o.getName(), "player", Material.PLAYER_HEAD, () -> {
                    pl.claims.eject(c, o);
                    pl.msg(o, "&cYou were kicked from &f" + c.name + "&c.");
                    pl.msg(p, "&aKicked " + o.getName() + ".");
                }));
            }
        }
        if (l.isEmpty()) {
            pl.msg(p, "&7Nobody to kick right now.");
            return;
        }
        l.add(back(() -> banMenu(p, c)));
        menu(p, "&6Kick Player", "Who do you want to kick?", l);
    }

    // ----- rent / sell -----

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void rentMenu(Player p, Claim c) {
        FileConfiguration f = pl.getConfig();
        double max = f.getDouble("rent.max-price", 100000);
        long hrs = f.getLong("rent.period-hours", 24);
        List<Btn> l = new ArrayList<>();
        l.add(b("&aSet Rent Price" + (c.rentPrice > 0 ? " &7(now " + pl.money(c.rentPrice) + ")" : ""), "rent", Material.GOLD_INGOT,
                () -> input(p, "&6Rent price", "&aEnter the rent price per " + hrs + "h (max " + max + ")", "100", s -> {
                    double v = parse(s);
                    if (v <= 0 || v > max) {
                        pl.msg(p, "&cInvalid price.");
                        return;
                    }
                    c.rentPrice = v;
                    pl.claims.markDirty();
                    pl.msg(p, "&aRent set to &e" + pl.money(v) + " &aper " + hrs + "h.");
                    claimMenu(p, c);
                })));
        l.add(b("&cStop Renting Out", "no", Material.RED_CONCRETE, () -> {
            c.rentPrice = 0;
            pl.claims.markDirty();
            pl.msg(p, "&7No longer for rent.");
            claimMenu(p, c);
        }));
        l.add(back(() -> claimMenu(p, c)));
        menu(p, "&6Rent - " + c.name, c.rented() ? "Rented by " + pl.claims.nameOf(c.renter) : "Not rented", l);
    }

    private void sellMenu(Player p, Claim c) {
        double max = pl.getConfig().getDouble("sell.max-price", 1000000);
        List<Btn> l = new ArrayList<>();
        l.add(b("&aSet Sale Price" + (c.salePrice > 0 ? " &7(now " + pl.money(c.salePrice) + ")" : ""), "sell", Material.EMERALD,
                () -> input(p, "&6Sell claim", "&aEnter the sale price (max " + max + ")", "1000", s -> {
                    double v = parse(s);
                    if (v <= 0 || v > max) {
                        pl.msg(p, "&cInvalid price.");
                        return;
                    }
                    c.salePrice = v;
                    pl.claims.markDirty();
                    pl.msg(p, "&aListed for &e" + pl.money(v) + "&a on the market.");
                    claimMenu(p, c);
                })));
        l.add(b("&cCancel Sale", "no", Material.RED_CONCRETE, () -> {
            c.salePrice = 0;
            pl.claims.markDirty();
            pl.msg(p, "&7Sale cancelled.");
            claimMenu(p, c);
        }));
        l.add(back(() -> claimMenu(p, c)));
        menu(p, "&6Sell - " + c.name, "Sell this claim", l);
    }

    public void market(Player p) {
        FileConfiguration f = pl.getConfig();
        boolean rentOn = f.getBoolean("rent.enabled"), sellOn = f.getBoolean("sell.enabled");
        List<Btn> l = new ArrayList<>();
        for (Claim c : pl.claims.all()) {
            if (c.owner.equals(p.getUniqueId())) continue;
            String who = pl.claims.nameOf(c.owner);
            if (rentOn && c.rentPrice > 0 && (!c.rented() || p.getUniqueId().equals(c.renter))) {
                l.add(b("&bRent: &f" + c.name + " &7by " + who + " &e" + pl.money(c.rentPrice), "rent", Material.GOLD_INGOT,
                        () -> confirm(p, "&eRent &f" + c.name + " &efor " + pl.money(c.rentPrice) + "?",
                                () -> pl.claims.rent(p, c), () -> market(p))));
            }
            if (sellOn && c.salePrice > 0) {
                l.add(b("&6Buy: &f" + c.name + " &7by " + who + " &e" + pl.money(c.salePrice), "sell", Material.EMERALD,
                        () -> confirm(p, "&eBuy &f" + c.name + " &efor " + pl.money(c.salePrice) + "?",
                                () -> pl.claims.buy(p, c), () -> market(p))));
            }
        }
        if (l.isEmpty()) {
            pl.msg(p, "&7Nothing is for rent or sale right now.");
            return;
        }
        l.add(back(() -> main(p)));
        menu(p, "&6Claim Market", "Rent or buy land:", l);
    }

    public void info(Player p, Claim c) {
        FileConfiguration f = pl.getConfig();
        pl.msg(p, "&a" + c.name + " &7- owner &f" + pl.claims.nameOf(c.owner) + "&7, chunks &f" + c.chunks.size()
                + "&7, trusted &f" + c.trusted.size());
        if (f.getBoolean("tax.enabled") && pl.eco() != null) {
            pl.msg(p, "&7Tax: &e" + pl.money(f.getDouble("tax.cost-per-chunk") * c.chunks.size())
                    + " &7every &f" + f.getLong("tax.interval-hours", 24) + "h");
        }
        if (c.rentPrice > 0) pl.msg(p, "&7For rent: &e" + pl.money(c.rentPrice) + (c.rented() ? " &7(rented)" : ""));
        if (c.salePrice > 0) pl.msg(p, "&7For sale: &e" + pl.money(c.salePrice));
    }
}
