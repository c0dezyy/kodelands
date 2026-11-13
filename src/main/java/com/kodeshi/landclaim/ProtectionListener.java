package com.kodeshi.landclaim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class ProtectionListener implements Listener {
    private final LandClaimPlugin pl;
    private final ClaimManager cm;

    private static final Set<Material> USE_NEEDS_BUILD = Set.of(
            Material.COMPOSTER, Material.JUKEBOX, Material.NOTE_BLOCK, Material.LECTERN, Material.BEACON,
            Material.RESPAWN_ANCHOR, Material.SWEET_BERRY_BUSH, Material.DECORATED_POT, Material.REPEATER,
            Material.COMPARATOR, Material.DAYLIGHT_DETECTOR, Material.CAKE, Material.FLOWER_POT,
            Material.CHISELED_BOOKSHELF);

    ProtectionListener(LandClaimPlugin pl) {
        this.pl = pl;
        this.cm = pl.claims;
    }

    private boolean on(String key) {
        return pl.getConfig().getBoolean("protection." + key, true);
    }

    /** True (and tells the player) if this player may NOT do this here. */
    private boolean deny(Player p, Location l, int perm) {
        Claim c = cm.at(l);
        if (c == null || cm.allowed(p, c, perm)) return false;
        p.sendActionBar(LandClaimPlugin.c("&cThis land belongs to &e" + cm.nameOf(c.owner)));
        return true;
    }

    private static Player shooter(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    private static boolean foreign(Claim origin, Claim c) {
        return c != null && (origin == null || !origin.owner.equals(c.owner));
    }

    // ---------- blocks ----------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation(), Claim.BREAK)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (deny(e.getPlayer(), e.getBlockPlaced().getLocation(), Claim.BUILD)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation(), Claim.BUILD)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation(), Claim.BUILD)) e.setCancelled(true);
    }

    private int need(PlayerInteractEvent e, Block b) {
        Material t = b.getType();
        if (e.getAction() == Action.PHYSICAL) {
            return (t == Material.FARMLAND || t == Material.TURTLE_EGG) ? Claim.BREAK : 0;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return 0;
        if (Tag.DOORS.isTagged(t) || Tag.TRAPDOORS.isTagged(t) || Tag.FENCE_GATES.isTagged(t)
                || Tag.BUTTONS.isTagged(t) || t == Material.LEVER) {
            return Claim.DOORS;
        }
        if (b.getState(false) instanceof Container) return Claim.CHESTS;
        String n = t.name();
        if (USE_NEEDS_BUILD.contains(t) || n.startsWith("POTTED_") || n.endsWith("CANDLE_CAKE")) return Claim.BUILD;
        ItemStack it = e.getItem();
        if (it != null) {
            String in = it.getType().name();
            if (in.endsWith("_SPAWN_EGG") || in.equals("FLINT_AND_STEEL") || in.equals("FIRE_CHARGE")
                    || in.equals("ARMOR_STAND") || in.endsWith("_BOAT") || in.endsWith("_RAFT")
                    || in.endsWith("MINECART") || in.equals("END_CRYSTAL") || in.endsWith("ITEM_FRAME")
                    || in.equals("PAINTING") || in.equals("BONE_MEAL") || in.endsWith("_AXE")
                    || in.endsWith("_SHOVEL") || in.endsWith("_HOE") || in.equals("SHEARS") || in.equals("BRUSH")) {
                return Claim.BUILD;
            }
        }
        return 0;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Claim c = cm.at(b);
        if (c == null) return;
        int need = need(e, b);
        if (need == 0) return;
        Player p = e.getPlayer();
        if (!cm.allowed(p, c, need)) {
            e.setCancelled(true);
            p.sendActionBar(LandClaimPlugin.c("&cThis land belongs to &e" + cm.nameOf(c.owner)));
        }
    }

    // ---------- entities ----------

    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        int need = 0;
        if (en instanceof Hanging || en instanceof ArmorStand) {
            need = Claim.BUILD;
        } else if (en instanceof StorageMinecart || en instanceof HopperMinecart
                || en instanceof ChestBoat || en instanceof ChestedHorse) {
            need = Claim.CHESTS;
        } else if (en instanceof Mob && !(en instanceof Enemy) && !(en instanceof AbstractVillager)) {
            Material m = e.getPlayer().getInventory().getItem(e.getHand()).getType();
            if (m == Material.LEAD || m == Material.SHEARS || m == Material.NAME_TAG || m == Material.BUCKET) {
                need = Claim.BUILD;
            }
        }
        if (need != 0 && deny(e.getPlayer(), en.getLocation(), need)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (deny(e.getPlayer(), e.getRightClicked().getLocation(), Claim.BUILD)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity v = e.getEntity();
        if (v instanceof Player || v instanceof Enemy) return;
        Player p = shooter(e.getDamager());
        if (p == null) {
            if (cm.at(v.getLocation()) == null) return;
            boolean boom = e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION && on("explosions");
            boolean deco = (v instanceof Hanging || v instanceof ArmorStand) && on("entities");
            if (boom || deco) e.setCancelled(true);
            return;
        }
        if (deny(p, v.getLocation(), Claim.BREAK)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreakBy(HangingBreakByEntityEvent e) {
        Player p = shooter(e.getRemover());
        Location l = e.getEntity().getLocation();
        if (p != null) {
            if (deny(p, l, Claim.BREAK)) e.setCancelled(true);
        } else if (on("entities") && cm.at(l) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION && on("explosions")
                && cm.at(e.getEntity().getLocation()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        Player p = e.getPlayer();
        if (p != null && deny(p, e.getEntity().getLocation(), Claim.BUILD)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent e) {
        Player p = shooter(e.getAttacker());
        if (p != null && deny(p, e.getVehicle().getLocation(), Claim.BREAK)) e.setCancelled(true);
    }

    // ---------- explosions / fire / liquids / pistons / mobs ----------

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (on("explosions")) e.blockList().removeIf(b -> cm.at(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (on("explosions")) e.blockList().removeIf(b -> cm.at(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (on("fire") && cm.at(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Player p = e.getPlayer();
        if (p != null) {
            if (deny(p, e.getBlock().getLocation(), Claim.BUILD)) e.setCancelled(true);
        } else if (on("fire") && cm.at(e.getBlock()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!on("liquid-flow")) return;
        Claim to = cm.at(e.getToBlock());
        if (to == null) return;
        Claim from = cm.at(e.getBlock());
        if (from == null || !from.owner.equals(to.owner)) e.setCancelled(true);
    }

    private boolean pistonBlocked(Block piston, List<Block> moved, BlockFace dir) {
        if (!on("pistons")) return false;
        Claim pc = cm.at(piston);
        for (Block b : moved) {
            if (foreign(pc, cm.at(b)) || foreign(pc, cm.at(b.getRelative(dir)))
                    || foreign(pc, cm.at(b.getRelative(dir.getOppositeFace())))) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistonBlocked(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistonBlocked(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (!on("mob-griefing")) return;
        Entity en = e.getEntity();
        if (en instanceof Player || en instanceof FallingBlock || en instanceof Villager) return;
        if (cm.at(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDoorBreak(EntityBreakDoorEvent e) {
        if (on("mob-griefing") && cm.at(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        Claim origin = cm.at(e.getLocation().getBlock());
        e.getBlocks().removeIf(bs -> foreign(origin, cm.at(bs.getBlock())));
    }

    // ---------- bans + enter/leave ----------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        if ((f.getBlockX() >> 4) == (t.getBlockX() >> 4) && (f.getBlockZ() >> 4) == (t.getBlockZ() >> 4)) return;
        Player p = e.getPlayer();
        Claim to = cm.at(t), from = cm.at(f);
        if (to == from) return;
        if (to != null && to.banned.contains(p.getUniqueId()) && !p.hasPermission("landclaim.bypass")) {
            e.setCancelled(true);
            p.sendActionBar(LandClaimPlugin.c("&cYou are banned from this land."));
            return;
        }
        String mode = pl.getConfig().getString("messages.enter-display", "TITLE").toUpperCase();
        if (mode.equals("NONE")) return;
        if (to != null) {
            Component name = LandClaimPlugin.c("&a" + to.name);
            Component owner = LandClaimPlugin.c("&7Claimed by &f" + cm.nameOf(to.owner));
            if (mode.equals("TITLE")) {
                p.showTitle(Title.title(name, owner, Title.Times.times(
                        Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(500))));
            } else {
                p.sendActionBar(LandClaimPlugin.c("&a" + to.name + " &7- claimed by &f" + cm.nameOf(to.owner)));
            }
        } else {
            p.sendActionBar(LandClaimPlugin.c("&7Leaving claimed land"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() == null) return;
        Claim c = cm.at(e.getTo());
        Player p = e.getPlayer();
        if (c != null && c.banned.contains(p.getUniqueId()) && !p.hasPermission("landclaim.bypass")) {
            e.setCancelled(true);
            p.sendActionBar(LandClaimPlugin.c("&cYou are banned from this land."));
        }
    }
}
