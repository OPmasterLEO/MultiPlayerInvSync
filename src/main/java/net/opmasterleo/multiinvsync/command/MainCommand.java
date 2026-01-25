package net.opmasterleo.multiinvsync.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;

public class MainCommand implements CommandExecutor, TabCompleter {
    
    private final MultiInvSyncPlugin plugin;
    
    public MainCommand(MultiInvSyncPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("multiinvsync.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }
        
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.reload();
                sender.sendMessage(ChatColor.GREEN + "MultiInvSync has been reloaded!");
                break;
                
            case "info":
                sendInfo(sender);
                break;
                
            case "bypass":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                    return true;
                }
                
                Player player = (Player) sender;
                if (args.length > 1 && args[1].equalsIgnoreCase("off")) {
                    plugin.getSyncManager().removeBypassPlayer(player.getUniqueId());
                    sender.sendMessage(ChatColor.GREEN + "Inventory sync bypass disabled.");
                } else {
                    plugin.getSyncManager().addBypassPlayer(player.getUniqueId());
                    sender.sendMessage(ChatColor.GREEN + "Inventory sync bypass enabled.");
                }
                break;
                
            default:
                sendHelp(sender);
                break;
        }
        
        return true;
    }
    
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== MultiInvSync Commands =====");
        sender.sendMessage(ChatColor.YELLOW + "/mis reload" + ChatColor.WHITE + " - Reload the plugin configuration");
        sender.sendMessage(ChatColor.YELLOW + "/mis info" + ChatColor.WHITE + " - Display plugin information");
        sender.sendMessage(ChatColor.YELLOW + "/mis bypass [on|off]" + ChatColor.WHITE + " - Toggle inventory sync bypass");
    }
    
    private void sendInfo(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== MultiInvSync Info =====");
        sender.sendMessage(ChatColor.YELLOW + "Version: " + ChatColor.WHITE + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.YELLOW + "Team Mode: " + ChatColor.WHITE + 
            (plugin.getConfigManager().isTeamsEnabled() ? "Enabled" : "Disabled"));
        sender.sendMessage(ChatColor.YELLOW + "Team Provider: " + ChatColor.WHITE + 
            plugin.getTeamManager().getActiveProviderName());
        sender.sendMessage(ChatColor.YELLOW + "Shared Death: " + ChatColor.WHITE + 
            (plugin.getConfigManager().isSharedDeath() ? "Enabled" : "Disabled"));
        sender.sendMessage(ChatColor.YELLOW + "Ender Chest Sync: " + ChatColor.WHITE + 
            (plugin.getConfigManager().isSyncEnderChest() ? "Enabled" : "Disabled"));
        sender.sendMessage(ChatColor.YELLOW + "Performance: " + ChatColor.WHITE + "NMS + Netty (Always On)");
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("multiinvsync.admin")) {
            return new ArrayList<>();
        }
        
        if (args.length == 1) {
            return filterStartingWith(args[0], Arrays.asList("reload", "info", "bypass"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("bypass")) {
            return filterStartingWith(args[1], Arrays.asList("on", "off"));
        }
        
        return new ArrayList<>();
    }
    
    private List<String> filterStartingWith(String input, List<String> options) {
        List<String> result = new ArrayList<>();
        String lowerInput = input.toLowerCase();
        
        for (String option : options) {
            if (option.toLowerCase().startsWith(lowerInput)) {
                result.add(option);
            }
        }
        
        return result;
    }
}
