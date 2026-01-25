package net.opmasterleo.multiinvsync.team;

import java.util.Collection;
import java.util.UUID;

import org.bukkit.entity.Player;

public interface TeamProvider {
    
    String getName();
    
    boolean isInTeam(Player player);
    
    UUID getTeamId(Player player);
    
    Collection<Player> getTeamMembers(Player player);
    
    boolean isAvailable();
}
