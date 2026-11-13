package com.kodeshi.landclaim;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Only ever loaded when Vault is installed, so the plugin runs fine without it. */
final class VaultHook {
    private final Economy eco;

    private VaultHook(Economy eco) {
        this.eco = eco;
    }

    static VaultHook create(Server server) {
        RegisteredServiceProvider<Economy> r = server.getServicesManager().getRegistration(Economy.class);
        return r == null ? null : new VaultHook(r.getProvider());
    }

    boolean has(OfflinePlayer p, double amt) {
        return eco.has(p, amt);
    }

    boolean withdraw(OfflinePlayer p, double amt) {
        return eco.withdrawPlayer(p, amt).transactionSuccess();
    }

    void deposit(OfflinePlayer p, double amt) {
        eco.depositPlayer(p, amt);
    }

    String format(double amt) {
        return eco.format(amt);
    }
}
