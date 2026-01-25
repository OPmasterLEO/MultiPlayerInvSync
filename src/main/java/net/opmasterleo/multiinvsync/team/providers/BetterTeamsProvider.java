package net.opmasterleo.multiinvsync.team.providers;

import com.booksaw.betterTeams.Team;
import net.opmasterleo.multiinvsync.team.TeamProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BetterTeamsProvider implements TeamProvider {
    
    @Override
    public String getName() {
        return "BetterTeams";
    }
    
    @Override
    public boolean isInTeam(Player player) {
        if (!isAvailable()) {
            return false;
        }
        return getTeam(player) != null;
    }
    
    @Override
    public UUID getTeamId(Player player) {
        if (!isAvailable() || !isInTeam(player)) {
            return null;
        }
        
        Team team = getTeam(player);
        if (team == null) {
            return null;
        }

        String id = invokeString(team, "getID");
        if (id == null) {
            id = invokeString(team, "getId");
        }

        if (id != null) {
            try {
                return UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
                return UUID.nameUUIDFromBytes(id.getBytes());
            }
        }

        String name = invokeString(team, "getName");
        if (name != null) {
            return UUID.nameUUIDFromBytes(name.getBytes());
        }

        return null;
    }
    
    @Override
    public Collection<Player> getTeamMembers(Player player) {
        Collection<Player> members = new ArrayList<>();
        
        if (!isAvailable() || !isInTeam(player)) {
            members.add(player);
            return members;
        }
        
        try {
            Team team = getTeam(player);
            if (team != null) {
                Set<Player> resolved = resolveMembers(team);
                if (!resolved.isEmpty()) {
                    members.addAll(resolved);
                }
            }
        } catch (Exception e) {
            members.add(player);
        }
        
        if (members.isEmpty()) {
            members.add(player);
        }
        
        return members;
    }
    
    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.booksaw.betterTeams.Team");
            return Bukkit.getPluginManager().getPlugin("BetterTeams") != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private Team getTeam(Player player) {
        try {
            Method method = Team.class.getMethod("getTeam", Player.class);
            return (Team) method.invoke(null, player);
        } catch (Exception ignored) {
        }

        try {
            Method method = Team.class.getMethod("getTeam", UUID.class);
            return (Team) method.invoke(null, player.getUniqueId());
        } catch (Exception ignored) {
        }
        return null;
    }

    private String invokeString(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object result = m.invoke(target);
            return result != null ? result.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<Player> resolveMembers(Team team) {
        Set<Player> players = new HashSet<>();

        try {
            // Try method getOnlinePlayers(): Collection<Player>
            Method onlinePlayers = team.getClass().getMethod("getOnlinePlayers");
            Object result = onlinePlayers.invoke(team);
            if (result instanceof Iterable<?> iterable) {
                for (Object obj : iterable) {
                    if (obj instanceof Player p && p.isOnline()) {
                        players.add(p);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (!players.isEmpty()) {
            return players;
        }

        try {
            // Try method getMembers() returning a component with getMembers()/getPlayers()
            Method getMembers = team.getClass().getMethod("getMembers");
            Object membersObj = getMembers.invoke(team);

            if (membersObj instanceof Iterable<?> iterable) {
                for (Object obj : iterable) {
                    Player p = extractPlayer(obj);
                    if (p != null && p.isOnline()) {
                        players.add(p);
                    }
                }
            } else if (membersObj != null) {
                try {
                    Method inner = membersObj.getClass().getMethod("getMembers");
                    Object innerResult = inner.invoke(membersObj);
                    if (innerResult instanceof Iterable<?> iterable) {
                        for (Object obj : iterable) {
                            Player p = extractPlayer(obj);
                            if (p != null && p.isOnline()) {
                                players.add(p);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        return players;
    }

    private Player extractPlayer(Object obj) {
        if (obj instanceof Player p) {
            return p;
        }

        try {
            Method m = obj.getClass().getMethod("getPlayer");
            Object result = m.invoke(obj);
            if (result instanceof Player p) {
                return p;
            }
        } catch (Exception ignored) {
        }

        try {
            Method m = obj.getClass().getMethod("getUniqueId");
            Object result = m.invoke(obj);
            if (result instanceof UUID uuid) {
                return Bukkit.getPlayer(uuid);
            }
        } catch (Exception ignored) {
        }

        return null;
    }
}
