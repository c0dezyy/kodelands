package com.kodeshi.landclaim;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClaimManager {
    private final LandClaimPlugin pl;
    private final Map<String, Claim> byId = new LinkedHashMap<>();
    private final Map<String, Map<Long, Claim>> index = new HashMap<>();
    private final Map<UUID, List<Claim>> byOwner = new HashMap<>();
    private final Map<UUID, Integer> extra = new HashMap<>();
    private boolean dirty;

    ClaimManager(LandClaimPlugin pl) {
        this.pl = pl;
    }

    // ---------- lookup ----------

    public Claim at(String world, int cx, int cz) {
        Map<Long, Claim> m = index.get(world);
        return m == null ? null : m.get(Claim.key(cx, cz));
    }

    public Claim at(Location l) {
        return l.getWorld() == null ? null : at(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }

    public Claim at(Block b) {
        return at(b.getWorld().getName(), b.getX() >> 4, b.getZ() >> 4);
    }

    public Collection<Claim> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public boolean alive(Claim c) {
        return c != null && byId.get(c.id) == c;
    }

    public List<Claim> owned(UUID u) {
        return byOwner.getOrDefault(u, Collections.emptyList());
    }

    public List<Claim> managed(UUID u) {
        List<Claim> l = new ArrayList<>();
        for (Claim c : byId.values()) {
            if (c.manages(u)) l.add(c);
        }
        return l;
    }

    public String nameOf(UUID u) {
        String n = Bukkit.getOfflinePlayer(u).getName();
        return n != null ? n : u.toString().substring(0, 8);
    }

    private static String strip(String s) {
        return s.startsWith(".") ? s.substring(1) : s;
    }

    /** Finds a player by name (online first, then cached). Handles the Bedrock "." prefix. */
    public OfflinePlayer find(String name) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (strip(o.getName()).equalsIgnoreCase(strip(name))) return o;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    // ---------- permissions ----------

    public boolean allowed(Player p, Claim c, int perm) {
        if (p.hasPermission("landclaim.bypass")) return true;
        UUID u = p.getUniqueId();
        if (c.manages(u) || (c.rented() && u.equals(c.renter))) return true;
        Integer m = c.trusted.get(u);
        return m != null && (m & perm) != 0;
    }

    public int defaultPerms() {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("default-trust");
        int m = 0;
        if (s == null) return Claim.DOORS;
        if (s.getBoolean("build")) m |= Claim.BUILD;
        if (s.getBoolean("break")) m |= Claim.BREAK;
        if (s.getBoolean("doors")) m |= Claim.DOORS;
        if (s.getBoolean("chests")) m |= Claim.CHESTS;
        return m;
    }

    // ---------- limits ----------

    public int limit(UUID u) {
        return pl.getConfig().getInt("claim.max-claims", 3) + extra.getOrDefault(u, 0);
    }

    public boolean needsExtra(UUID u) {
        return owned(u).size() >= limit(u);
    }

    public boolean canBuyExtra(UUID u) {
        FileConfiguration f = pl.getConfig();
        return f.getBoolean("claim.extra-claims.enabled")
                && extra.getOrDefault(u, 0) < f.getInt("claim.extra-claims.max-extra", 2);
    }

    public double newCost(UUID u) {
        FileConfiguration f = pl.getConfig();
        return f.getDouble("claim.price", 0) + (needsExtra(u) ? f.getDouble("claim.extra-claims.price", 0) : 0);
    }

    // ---------- create / expand ----------

    public String canStartNew(Player p) {
        if (pl.getConfig().getStringList("disabled-worlds").contains(p.getWorld().getName())) {
            return "Claims are disabled in this world.";
        }
        if (needsExtra(p.getUniqueId()) && !canBuyExtra(p.getUniqueId())) {
            return "You reached your claim limit (" + limit(p.getUniqueId()) + ").";
        }
        return null;
    }

    /** Returns an error message, or null if a new claim could be placed on this chunk. */
    public String checkNew(Player p, int cx, int cz) {
        String e = canStartNew(p);
        if (e != null) return e;
        Claim o = at(p.getWorld().getName(), cx, cz);
        if (o != null) return o.owner.equals(p.getUniqueId()) ? "This chunk is already your claim." : "This land is claimed by " + nameOf(o.owner) + ".";
        return null;
    }

    public Claim create(Player p, World w, String name, int cx, int cz) {
        name = name.trim();
        if (!name.matches("[A-Za-z0-9 _\\-]{1,24}")) {
            pl.msg(p, "&cName must be 1-24 letters, numbers, spaces, _ or -.");
            return null;
        }
        if (p.getWorld() != w) return null;
        String err = checkNew(p, cx, cz);
        if (err != null) {
            pl.msg(p, "&c" + err);
            return null;
        }
        for (Claim o : owned(p.getUniqueId())) {
            if (o.name.equalsIgnoreCase(name)) {
                pl.msg(p, "&cYou already have a claim with that name.");
                return null;
            }
        }
        boolean ex = needsExtra(p.getUniqueId());
        if (!pl.charge(p, newCost(p.getUniqueId()))) return null;
        if (ex) extra.merge(p.getUniqueId(), 1, Integer::sum);
        Claim c = new Claim(UUID.randomUUID().toString().substring(0, 8), p.getUniqueId(), name, w.getName());
        c.chunks.add(Claim.key(cx, cz));
        register(c);
        dirty = true;
        return c;
    }

    public String checkExpand(Player p, Claim c, int cx, int cz) {
        FileConfiguration f = pl.getConfig();
        if (!f.getBoolean("expand.enabled", true)) return "Expanding is disabled.";
        if (!c.manages(p.getUniqueId())) return "You can't expand this claim.";
        if (!c.world.equals(p.getWorld().getName())) return "This claim is in another world.";
        Claim o = at(c.world, cx, cz);
        if (o == c) return "This chunk is already part of the claim.";
        if (o != null) return "This land is claimed by " + nameOf(o.owner) + ".";
        int maxExpand = f.getInt("expand.max-expand", 8);
        if (maxExpand >= 0 && c.chunks.size() - 1 >= maxExpand) return "Max expansion reached (" + maxExpand + " chunks).";
        if (f.getBoolean("expand.require-adjacent", true) && !c.touches(cx, cz)) return "Must touch your claim.";
        return null;
    }

    public boolean expand(Player p, Claim c, int cx, int cz) {
        String err = checkExpand(p, c, cx, cz);
        if (err != null) {
            pl.msg(p, "&c" + err);
            return false;
        }
        FileConfiguration f = pl.getConfig();
        double price = f.getBoolean("expand.price-enabled") ? f.getDouble("expand.price-per-chunk") : 0;
        if (!pl.charge(p, price)) return false;
        long k = Claim.key(cx, cz);
        c.chunks.add(k);
        index.computeIfAbsent(c.world, w -> new HashMap<>()).put(k, c);
        int merged = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            Claim o = at(c.world, cx + d[0], cz + d[1]);
            if (o != null && o != c && o.owner.equals(c.owner)) {
                merge(c, o);
                merged++;
            }
        }
        dirty = true;
        pl.msg(p, "&aClaim expanded." + (merged > 0 ? " &7Merged with " + merged + " nearby claim(s)." : ""));
        return true;
    }

    private void merge(Claim c, Claim o) {
        Map<Long, Claim> m = index.get(c.world);
        for (long k : o.chunks) {
            c.chunks.add(k);
            m.put(k, c);
        }
        o.trusted.forEach((u, perm) -> c.trusted.merge(u, perm, (a, b) -> a | b));
        c.coOwners.addAll(o.coOwners);
        c.banned.addAll(o.banned);
        byId.remove(o.id);
        List<Claim> l = byOwner.get(o.owner);
        if (l != null) l.remove(o);
    }

    public void rename(Player p, Claim c, String name) {
        name = name.trim();
        if (!name.matches("[A-Za-z0-9 _\\-]{1,24}")) {
            pl.msg(p, "&cName must be 1-24 letters, numbers, spaces, _ or -.");
            return;
        }
        for (Claim o : owned(c.owner)) {
            if (o != c && o.name.equalsIgnoreCase(name)) {
                pl.msg(p, "&cYou already have a claim with that name.");
                return;
            }
        }
        c.name = name;
        dirty = true;
        pl.msg(p, "&aRenamed to &f" + name);
    }

    // ---------- register / delete ----------

    private void register(Claim c) {
        byId.put(c.id, c);
        Map<Long, Claim> m = index.computeIfAbsent(c.world, w -> new HashMap<>());
        for (long k : c.chunks) m.put(k, c);
        byOwner.computeIfAbsent(c.owner, u -> new ArrayList<>()).add(c);
    }

    public void delete(Claim c) {
        byId.remove(c.id);
        Map<Long, Claim> m = index.get(c.world);
        if (m != null) {
            for (long k : c.chunks) m.remove(k);
        }
        List<Claim> l = byOwner.get(c.owner);
        if (l != null) l.remove(c);
        dirty = true;
    }

    public void markDirty() {
        dirty = true;
    }

    public void touch(UUID u) {
        long now = System.currentTimeMillis();
        for (Claim c : owned(u)) c.lastActive = now;
        dirty = true;
    }

    // ---------- teleport / eject ----------

    public void teleport(Player p, Claim c) {
        World w = Bukkit.getWorld(c.world);
        if (w == null || c.chunks.isEmpty()) {
            pl.msg(p, "&cThat claim's world isn't loaded.");
            return;
        }
        long k = c.chunks.iterator().next();
        int x = Claim.cx(k) * 16 + 8, z = Claim.cz(k) * 16 + 8;
        Location dest = new Location(w, x + 0.5, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z + 0.5);
        int delay = pl.getConfig().getInt("teleport.delay-seconds", 3);
        p.closeInventory();
        if (delay <= 0) {
            p.teleportAsync(dest);
            return;
        }
        Location start = p.getLocation();
        pl.msg(p, "&7Teleporting in &e" + delay + "s&7, don't move...");
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline()) return;
            if (p.getWorld() != start.getWorld() || p.getLocation().distanceSquared(start) > 2) {
                pl.msg(p, "&cTeleport cancelled.");
                return;
            }
            p.teleportAsync(dest);
        }, delay * 20L);
    }

    /** Moves a player to the nearest chunk that isn't part of this claim. */
    public void eject(Claim c, Player t) {
        World w = t.getWorld();
        int cx = t.getLocation().getBlockX() >> 4, cz = t.getLocation().getBlockZ() >> 4;
        for (int r = 1; r < 64; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (at(w.getName(), cx + dx, cz + dz) == c) continue;
                    int x = (cx + dx) * 16 + 8, z = (cz + dz) * 16 + 8;
                    t.teleportAsync(new Location(w, x + 0.5, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z + 0.5));
                    return;
                }
            }
        }
        t.teleportAsync(w.getSpawnLocation());
    }

    // ---------- admin helpers ----------

    /** Finds claims by name. "Name@owner" narrows to one owner. */
    public List<Claim> findByName(String raw) {
        String n = raw.trim(), owner = null;
        int at = n.lastIndexOf('@');
        if (at > 0) {
            owner = n.substring(at + 1).trim();
            n = n.substring(0, at).trim();
        }
        List<Claim> r = new ArrayList<>();
        for (Claim c : byId.values()) {
            if (c.name.equalsIgnoreCase(n) && (owner == null || strip(nameOf(c.owner)).equalsIgnoreCase(strip(owner)))) {
                r.add(c);
            }
        }
        return r;
    }

    /** Gives a claim to another player (admin). Trusted players stay, the old owner loses access. */
    public void transfer(Claim c, UUID to) {
        List<Claim> l = byOwner.get(c.owner);
        if (l != null) l.remove(c);
        c.owner = to;
        byOwner.computeIfAbsent(to, k -> new ArrayList<>()).add(c);
        c.trusted.remove(to);
        c.coOwners.remove(to);
        c.banned.remove(to);
        c.renter = null;
        c.rentUntil = 0;
        c.rentPrice = 0;
        c.salePrice = 0;
        c.nextTax = 0;
        c.missedTax = 0;
        c.lastActive = System.currentTimeMillis();
        String base = c.name;
        int n = 2;
        while (nameTaken(to, c)) {
            String suffix = " " + n++;
            c.name = (base.length() + suffix.length() > 24 ? base.substring(0, 24 - suffix.length()) : base) + suffix;
        }
        dirty = true;
    }

    private boolean nameTaken(UUID owner, Claim self) {
        for (Claim o : owned(owner)) {
            if (o != self && o.name.equalsIgnoreCase(self.name)) return true;
        }
        return false;
    }

    // ---------- rent / sell ----------

    public void rent(Player p, Claim c) {
        FileConfiguration f = pl.getConfig();
        if (!f.getBoolean("rent.enabled") || c.rentPrice <= 0) {
            pl.msg(p, "&cThis claim isn't for rent.");
            return;
        }
        if (c.owner.equals(p.getUniqueId())) return;
        boolean mine = p.getUniqueId().equals(c.renter);
        if (c.rented() && !mine) {
            pl.msg(p, "&cThis claim is already rented.");
            return;
        }
        if (!pl.charge(p, c.rentPrice)) return;
        pl.pay(c.owner, c.rentPrice);
        long period = f.getLong("rent.period-hours", 24) * 3600000L;
        long base = mine ? Math.max(c.rentUntil, System.currentTimeMillis()) : System.currentTimeMillis();
        c.renter = p.getUniqueId();
        c.rentUntil = base + period;
        dirty = true;
        pl.msg(p, "&aYou rented &f" + c.name + "&a for " + f.getLong("rent.period-hours", 24) + "h.");
    }

    public void buy(Player p, Claim c) {
        FileConfiguration f = pl.getConfig();
        if (!f.getBoolean("sell.enabled") || c.salePrice <= 0) {
            pl.msg(p, "&cThis claim isn't for sale.");
            return;
        }
        UUID u = p.getUniqueId();
        if (c.owner.equals(u)) return;
        if (needsExtra(u)) {
            pl.msg(p, "&cYou reached your claim limit.");
            return;
        }
        for (Claim o : owned(u)) {
            if (o.name.equalsIgnoreCase(c.name)) {
                pl.msg(p, "&cYou already own a claim with this name. Rename it first.");
                return;
            }
        }
        double price = c.salePrice;
        if (!pl.charge(p, price)) return;
        double cut = price * (1.0 - f.getDouble("sell.tax-percent", 5.0) / 100.0);
        pl.pay(c.owner, cut);
        List<Claim> l = byOwner.get(c.owner);
        if (l != null) l.remove(c);
        c.owner = u;
        byOwner.computeIfAbsent(u, k -> new ArrayList<>()).add(c);
        c.trusted.clear();
        c.coOwners.clear();
        c.banned.clear();
        c.renter = null;
        c.rentUntil = 0;
        c.rentPrice = 0;
        c.salePrice = 0;
        c.lastActive = System.currentTimeMillis();
        dirty = true;
        pl.msg(p, "&aYou bought &f" + c.name + "&a!");
    }

    // ---------- maintenance (tax, rent expiry, inactive) ----------

    void startTasks() {
        Bukkit.getScheduler().runTaskTimer(pl, () -> flush(false), 600L, 600L);
        Bukkit.getScheduler().runTaskTimer(pl, this::maintenance, 1200L, 12000L);
    }

    private void maintenance() {
        FileConfiguration f = pl.getConfig();
        long now = System.currentTimeMillis();
        boolean inactive = f.getBoolean("inactive.enabled");
        long inactiveMs = f.getLong("inactive.days", 30) * 86400000L;
        VaultHook eco = pl.eco();
        boolean tax = f.getBoolean("tax.enabled") && eco != null;
        long interval = f.getLong("tax.interval-hours", 24) * 3600000L;
        for (Claim c : new ArrayList<>(byId.values())) {
            if (c.renter != null && now >= c.rentUntil) {
                c.renter = null;
                dirty = true;
            }
            Player owner = Bukkit.getPlayer(c.owner);
            if (owner != null) c.lastActive = now;
            if (inactive) {
                long last = Math.max(c.lastActive, Bukkit.getOfflinePlayer(c.owner).getLastPlayed());
                if (now - last > inactiveMs) {
                    pl.getLogger().info("Deleted inactive claim '" + c.name + "' of " + nameOf(c.owner));
                    delete(c);
                    continue;
                }
            }
            if (tax) {
                if (c.nextTax == 0) {
                    c.nextTax = now + interval;
                    dirty = true;
                }
                if (now >= c.nextTax) {
                    double cost = f.getDouble("tax.cost-per-chunk", 0) * c.chunks.size();
                    OfflinePlayer op = Bukkit.getOfflinePlayer(c.owner);
                    if (cost <= 0 || (eco.has(op, cost) && eco.withdraw(op, cost))) {
                        c.missedTax = 0;
                    } else {
                        c.missedTax++;
                        if (owner != null) {
                            pl.msg(owner, "&cYou couldn't pay the tax for &f" + c.name + "&c (" + c.missedTax + "/" + f.getInt("tax.max-missed", 3) + ").");
                        }
                        if (c.missedTax > f.getInt("tax.max-missed", 3)) {
                            delete(c);
                            continue;
                        }
                    }
                    c.nextTax = now + interval;
                    dirty = true;
                }
            }
        }
    }

    // ---------- storage ----------

    void load() {
        File file = new File(pl.getDataFolder(), "claims.yml");
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ex = y.getConfigurationSection("extra");
        if (ex != null) {
            for (String k : ex.getKeys(false)) extra.put(UUID.fromString(k), ex.getInt(k));
        }
        ConfigurationSection cs = y.getConfigurationSection("claims");
        if (cs == null) return;
        for (String id : cs.getKeys(false)) {
            ConfigurationSection s = cs.getConfigurationSection(id);
            if (s == null) continue;
            Claim c = new Claim(id, UUID.fromString(s.getString("owner", "")), s.getString("name", id), s.getString("world", "world"));
            for (String k : s.getStringList("chunks")) {
                String[] p = k.split(",");
                c.chunks.add(Claim.key(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
            }
            ConfigurationSection t = s.getConfigurationSection("trusted");
            if (t != null) {
                for (String u : t.getKeys(false)) c.trusted.put(UUID.fromString(u), t.getInt(u));
            }
            for (String u : s.getStringList("coowners")) c.coOwners.add(UUID.fromString(u));
            for (String u : s.getStringList("banned")) c.banned.add(UUID.fromString(u));
            c.lastActive = s.getLong("lastActive", System.currentTimeMillis());
            c.rentPrice = s.getDouble("rentPrice");
            String r = s.getString("renter");
            if (r != null) c.renter = UUID.fromString(r);
            c.rentUntil = s.getLong("rentUntil");
            c.salePrice = s.getDouble("salePrice");
            c.nextTax = s.getLong("nextTax");
            c.missedTax = s.getInt("missedTax");
            register(c);
        }
    }

    void flush(boolean sync) {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : extra.entrySet()) y.set("extra." + e.getKey(), e.getValue());
        for (Claim c : byId.values()) {
            String p = "claims." + c.id + ".";
            y.set(p + "owner", c.owner.toString());
            y.set(p + "name", c.name);
            y.set(p + "world", c.world);
            List<String> ch = new ArrayList<>();
            for (long k : c.chunks) ch.add(Claim.cx(k) + "," + Claim.cz(k));
            y.set(p + "chunks", ch);
            for (Map.Entry<UUID, Integer> t : c.trusted.entrySet()) y.set(p + "trusted." + t.getKey(), t.getValue());
            y.set(p + "coowners", c.coOwners.stream().map(UUID::toString).toList());
            y.set(p + "banned", c.banned.stream().map(UUID::toString).toList());
            y.set(p + "lastActive", c.lastActive);
            y.set(p + "rentPrice", c.rentPrice);
            y.set(p + "renter", c.renter == null ? null : c.renter.toString());
            y.set(p + "rentUntil", c.rentUntil);
            y.set(p + "salePrice", c.salePrice);
            y.set(p + "nextTax", c.nextTax);
            y.set(p + "missedTax", c.missedTax);
        }
        String data = y.saveToString();
        File file = new File(pl.getDataFolder(), "claims.yml");
        Runnable write = () -> {
            try {
                pl.getDataFolder().mkdirs();
                Files.writeString(file.toPath(), data, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                pl.getLogger().severe("Could not save claims.yml: " + ex.getMessage());
            }
        };
        if (sync) write.run();
        else Bukkit.getScheduler().runTaskAsynchronously(pl, write);
    }
}
