package com.kodeshi.landclaim;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A claim is a set of 16x16 chunks in one world. */
public final class Claim {
    public static final int BUILD = 1, BREAK = 2, DOORS = 4, CHESTS = 8;

    public final String id;
    public UUID owner;
    public String name;
    public final String world;
    public final Set<Long> chunks = new HashSet<>();
    public final Map<UUID, Integer> trusted = new HashMap<>();
    public final Set<UUID> coOwners = new HashSet<>();
    public final Set<UUID> banned = new HashSet<>();
    public long lastActive = System.currentTimeMillis();
    public double rentPrice, salePrice;
    public UUID renter;
    public long rentUntil, nextTax;
    public int missedTax;

    public Claim(String id, UUID owner, String name, String world) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.world = world;
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int cx(long k) {
        return (int) (k >> 32);
    }

    public static int cz(long k) {
        return (int) k;
    }

    public boolean manages(UUID u) {
        return owner.equals(u) || coOwners.contains(u);
    }

    public boolean rented() {
        return renter != null && System.currentTimeMillis() < rentUntil;
    }

    public boolean touches(int cx, int cz) {
        return chunks.contains(key(cx + 1, cz)) || chunks.contains(key(cx - 1, cz))
                || chunks.contains(key(cx, cz + 1)) || chunks.contains(key(cx, cz - 1));
    }
}
