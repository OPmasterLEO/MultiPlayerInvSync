package net.opmasterleo.multiinvsync.economy;

import org.bukkit.entity.Player;

public interface MoneyProvider {
    String getName();
    boolean isAvailable();
    double getBalance(Player player);
    void setBalance(Player player, double amount);
    void shutdown();
}