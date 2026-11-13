package com.kodeshi.landclaim;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Borders are client-side only (sendBlockChange), so only the player who sees them is affected.
 * Nothing in the real world is ever modified, and original blocks are re-sent when out of range.
 */
public final class BorderManager {
    public enum Mode { NONE, NEW, EXPAND }

    public static final class Sess {
        public Mode mode = Mode.NONE;
        Claim claim;
        World world;
        boolean showOwn;
        final Map<Pos, Material> sent = new HashMap<>();
    }

    record Pos(int x, int y, int z) {
    }

    private final LandClaimPlugin pl;
    private final Map<UUID, Sess> sessions = new HashMap<>();
    private BukkitTask task;

    BorderManager(LandClaimPlugin pl) {
        this.pl = pl;
    }

    void start() {
        long ticks = Math.max(2, pl.getConfig().getInt("border.update-ticks", 5));
        task = Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, ticks);
    }

    void stop() {
        if (task != null) task.cancel();
        for (Map.Entry<UUID, Sess> e : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            Sess s = e.getValue();
            if (p == null || s.world == null) continue;
            for (Pos pos : s.sent.keySet()) restore(p, s.world, pos);
        }
        sessions.clear();
    }

    public Sess peek(Player p) {
        return sessions.get(p.getUniqueId());
    }

    private Sess get(Player p) {
        return sessions.computeIfAbsent(p.getUniqueId(), k -> new Sess());
    }

    public void remove(Player p) {
        sessions.remove(p.getUniqueId());
    }

    // ---------- actions ----------

    public void startNew(Player p) {
        String err = pl.claims.canStartNew(p);
        if (err != null) {
            pl.msg(p, "&c" + err);
            return;
        }
        p.closeInventory();
        Sess s = get(p);
        s.mode = Mode.NEW;
        s.claim = null;
        double cost = pl.claims.newCost(p.getUniqueId());
        pl.msg(p, "&aWalk to the spot you want, the green border follows you. Then right-click your &eClaim Menu &aitem (or type &e/confirm&a) to confirm."
                + (cost > 0 ? " &7Cost: &e" + pl.money(cost) : ""));
    }

    public void startExpand(Player p, Claim c) {
        p.closeInventory();
        Sess s = get(p);
        s.mode = Mode.EXPAND;
        s.claim = c;
        pl.msg(p, "&aWalk next to your claim, the border shows the new size. Right-click your &eClaim Menu &aitem (or type &e/confirm&a) to add the chunk, or cancel when done.");
    }

    public void cancel(Player p) {
        Sess s = peek(p);
        if (s == null || s.mode == Mode.NONE) {
            pl.msg(p, "&7Nothing to cancel.");
            return;
        }
        s.mode = Mode.NONE;
        s.claim = null;
        pl.msg(p, "&7Cancelled.");
    }

    public void toggleOwn(Player p) {
        Sess s = get(p);
        s.showOwn = !s.showOwn;
        pl.msg(p, s.showOwn ? "&aYour claim borders are now visible to you." : "&7Claim borders hidden.");
    }

    public void confirm(Player p) {
        Sess s = peek(p);
        if (s == null || s.mode == Mode.NONE) {
            pl.msg(p, "&7Nothing to confirm. Open your Claim Menu and pick &fAdd Claim&7.");
            return;
        }
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        World w = p.getWorld();
        if (s.mode == Mode.NEW) {
            String err = pl.claims.checkNew(p, cx, cz);
            if (err != null) {
                pl.msg(p, "&c" + err);
                return;
            }
            pl.gui.input(p, "&2Name your claim", "&aEnter a name for your claim", "My Land", name -> {
                Claim c = pl.claims.create(p, w, name, cx, cz);
                if (c == null) return;
                s.mode = Mode.NONE;
                s.claim = null;
                if (pl.getConfig().getBoolean("border.show-after-claim", true)) s.showOwn = true;
                pl.msg(p, "&aClaim &f" + c.name + " &acreated! Only you can see the border.");
            });
        } else if (pl.claims.alive(s.claim)) {
            pl.claims.expand(p, s.claim, cx, cz);
        } else {
            s.mode = Mode.NONE;
        }
    }

    // ---------- rendering ----------

    private Material mat(String path, Material def) {
        Material m = Material.matchMaterial(pl.getConfig().getString(path, def.name()));
        return m == null ? def : m;
    }

    private void tick() {
        if (sessions.isEmpty()) return;
        Material prev = mat("border.preview-block", Material.LIME_CONCRETE);
        Material bad = mat("border.invalid-block", Material.RED_CONCRETE);
        Material own = mat("border.owned-block", Material.GREEN_CONCRETE);
        int range = pl.getConfig().getInt("border.range", 24);
        for (Iterator<Map.Entry<UUID, Sess>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Sess> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            Sess s = en.getValue();
            update(p, s, prev, bad, own, range);
            if (s.mode == Mode.NONE && !s.showOwn && s.sent.isEmpty()) it.remove();
        }
    }

    private void update(Player p, Sess s, Material prev, Material bad, Material own, int range) {
        World w = p.getWorld();
        if (s.world != w) {
            s.sent.clear();
            s.world = w;
            if (s.mode != Mode.NONE) {
                s.mode = Mode.NONE;
                s.claim = null;
                pl.msg(p, "&7Claim preview cancelled (changed world).");
            }
        }
        if (s.mode == Mode.EXPAND && !pl.claims.alive(s.claim)) {
            s.mode = Mode.NONE;
            s.claim = null;
        }
        Location loc = p.getLocation();
        int px = loc.getBlockX(), pz = loc.getBlockZ(), cx = px >> 4, cz = pz >> 4;
        UUID u = p.getUniqueId();

        Set<Long> previewSet = null;
        Material previewMat = prev;
        Set<Claim> skip = new HashSet<>();
        if (s.mode == Mode.NEW) {
            String err = pl.claims.checkNew(p, cx, cz);
            previewSet = Set.of(Claim.key(cx, cz));
            if (err != null) previewMat = bad;
            hint(p, err);
        } else if (s.mode == Mode.EXPAND) {
            String err = pl.claims.checkExpand(p, s.claim, cx, cz);
            previewSet = new HashSet<>(s.claim.chunks);
            previewSet.add(Claim.key(cx, cz));
            skip.add(s.claim);
            if (err == null) {
                // merge: any of your other claims touching this chunk joins the preview outline
                for (Claim o : pl.claims.owned(u)) {
                    if (o != s.claim && o.world.equals(w.getName()) && o.touches(cx, cz)) {
                        previewSet.addAll(o.chunks);
                        skip.add(o);
                    }
                }
            } else {
                previewMat = bad;
            }
            hint(p, err);
        }

        Map<Pos, Material> want = new HashMap<>();
        if (s.showOwn) {
            for (Claim c : pl.claims.owned(u)) {
                if (c.world.equals(w.getName()) && !skip.contains(c)) outline(c.chunks, own, want, w, px, pz, range);
            }
        }
        if (previewSet != null) outline(previewSet, previewMat, want, w, px, pz, range);

        // restore blocks that are no longer part of the border
        for (Iterator<Map.Entry<Pos, Material>> it = s.sent.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Pos, Material> e = it.next();
            if (!want.containsKey(e.getKey())) {
                restore(p, w, e.getKey());
                it.remove();
            }
        }
        // send new / changed border blocks
        for (Map.Entry<Pos, Material> e : want.entrySet()) {
            if (s.sent.get(e.getKey()) != e.getValue()) {
                Pos k = e.getKey();
                p.sendBlockChange(new Location(w, k.x(), k.y(), k.z()), e.getValue().createBlockData());
                s.sent.put(k, e.getValue());
            }
        }
    }

    private void hint(Player p, String err) {
        p.sendActionBar(LandClaimPlugin.c(err == null
                ? "&aValid spot &7- right-click the Claim Menu item to confirm"
                : "&c" + err));
    }

    /** Draws only the outer edges of a chunk set, so touching chunks merge into one border. */
    private void outline(Set<Long> chunks, Material m, Map<Pos, Material> out, World w, int px, int pz, int range) {
        for (long k : chunks) {
            int cx = Claim.cx(k), cz = Claim.cz(k);
            int minX = cx * 16, minZ = cz * 16;
            if (minX + 16 < px - range || minX > px + range || minZ + 16 < pz - range || minZ > pz + range) continue;
            boolean west = !chunks.contains(Claim.key(cx - 1, cz));
            boolean east = !chunks.contains(Claim.key(cx + 1, cz));
            boolean north = !chunks.contains(Claim.key(cx, cz - 1));
            boolean south = !chunks.contains(Claim.key(cx, cz + 1));
            for (int i = 0; i < 16; i++) {
                if (west) add(out, w, minX, minZ + i, m, px, pz, range);
                if (east) add(out, w, minX + 15, minZ + i, m, px, pz, range);
                if (north) add(out, w, minX + i, minZ, m, px, pz, range);
                if (south) add(out, w, minX + i, minZ + 15, m, px, pz, range);
            }
        }
    }

    private void add(Map<Pos, Material> out, World w, int x, int z, Material m, int px, int pz, int range) {
        if (Math.abs(x - px) > range || Math.abs(z - pz) > range) return;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        out.put(new Pos(x, y, z), m);
    }

    private void restore(Player p, World w, Pos pos) {
        if (!w.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return;
        Block b = w.getBlockAt(pos.x(), pos.y(), pos.z());
        p.sendBlockChange(b.getLocation(), b.getBlockData());
    }
}
