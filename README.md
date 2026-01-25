# MultiPlayerInvSync

A high-performance Minecraft plugin that synchronizes player inventories globally or within teams using PaperMC's NMS and Netty for maximum efficiency.

## 🚀 Features

### Core Functionality
- **Real-time Inventory Synchronization**: All inventory changes are instantly synchronized using NMS + Netty
- **Full Inventory Support**: Syncs main inventory (36 slots), armor (4 slots), offhand (1 slot), and ender chest (27 slots)
- **Team-Based Synchronization**: Optional team-based inventory sharing with BetterTeams support
- **Shared Economy (optional)**: Sync player balances across the same groups using EssentialsX
- **Experience Sync (optional)**: Mirror XP level and progress across the same group
- **Shared Death System**: When one player dies, all synchronized inventories are cleared (items only drop for the player who died)
- **High Performance**: Uses NMS and Netty packet interception for optimal performance (always enabled)

### Technical Features
- **Paper & Folia Compatible**: Works on both Paper and Folia servers
- **NMS Integration**: Direct access to Minecraft internals for optimal performance
- **Netty Packet Interception**: Low-level networking for instant inventory updates
- **Async Processing**: Non-blocking operations to maintain server TPS
- **KeepInventory Aware**: Respects the keepInventory game rule

### Team Integration
- **BetterTeams Support**: Native integration with BetterTeams plugin
- **Extensible Architecture**: Easy to add support for other team plugins
- **Fallback Options**: Configurable fallback to global sync when no team plugin is found
- **Auto-Detection**: Automatically detects and uses available team plugins

## 📦 Installation

### Requirements
- Java 21 or higher
- Paper 1.21.1 or higher (or Folia)
- Gradle 8.x or higher (for building)

### Building from Source

```bash
git clone https://github.com/yourusername/MultiPlayerInvSync.git
cd MultiPlayerInvSync
./gradlew build
```

The compiled JAR will be in `build/libs/MultiPlayerInvSync-1.0.0.jar`

### Installation Steps

1. Download or build the plugin JAR
2. Place it in your server's `plugins` folder
3. Restart your server
4. Configure in `plugins/MultiPlayerInvSync/config.yml`
5. Use `/mis reload` to apply changes

## ⚙️ Configuration

```yaml
# Enable or disable the plugin
enabled: true

# Synchronization settings
sync:
  main-inventory: true    # Sync main inventory slots
  armor: true             # Sync armor slots
  offhand: true           # Sync offhand slot
  ender-chest: true       # Sync ender chest
  delay-ticks: 1          # Sync delay (1 = instant)
  experience: false       # Sync XP level + progress

# Economy synchronization
economy:
  sync-money: false       # Sync balances across the same targets as inventories (global or team)
  provider: "essentials"  # essentials | auto (currently auto tries EssentialsX)

# Death behavior
death:
  shared-death: true      # Clear all inventories when one player dies
  broadcast-shared-death: true
  death-message: "&c{player} died! All synchronized inventories have been cleared."

# Team integration
teams:
  enabled: false          # Enable team-based sync
  plugin: "auto"          # Team plugin (betterteams, auto)
  fallback-to-global: true

# Debug settings
debug:
  enabled: false
  log-sync-events: false
```

### Performance Note
NMS and Netty are **always enabled** for maximum performance. There are no configuration options to disable them.

## 🎮 Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/multiinvsync` or `/mis` | Main command | `multiinvsync.admin` |
| `/mis reload` | Reload configuration | `multiinvsync.admin` |
| `/mis info` | Display plugin information | `multiinvsync.admin` |
| `/mis bypass [on\|off]` | Toggle inventory sync bypass | `multiinvsync.admin` |

## 🔒 Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `multiinvsync.admin` | Access to all commands | `op` |
| `multiinvsync.bypass` | Bypass inventory synchronization | `false` |

## 💻 API Usage

### Getting the API

```java
import net.opmasterleo.multiinvsync.MultiInvSyncPlugin;
import net.opmasterleo.multiinvsync.api.MultiInvSyncAPI;

// Get the plugin instance
MultiInvSyncPlugin plugin = MultiInvSyncPlugin.getInstance();

// Get the API
MultiInvSyncAPI api = plugin.getAPI();
```

### API Methods

#### Sync a Player's Inventory
```java
Player player = ...; // Your player
api.syncInventory(player);
```

#### Clear All Synchronized Inventories
```java
Player player = ...; // The player who triggered the clear
boolean clearSource = false; // Don't clear the source player's inventory

api.clearAllInventories(player, clearSource);
```

#### Bypass System
```java
UUID playerUUID = player.getUniqueId();

// Add bypass (player's inventory won't sync)
api.addBypass(playerUUID);

// Remove bypass
api.removeBypass(playerUUID);

// Check if bypassed
if (api.isBypassed(playerUUID)) {
    // Player is bypassed
}
```

#### Get Sync Group
```java
Player player = ...;

// Get all players who share inventory with this player
Collection<Player> syncGroup = api.getSyncGroup(player);

// Check if team mode is enabled
if (api.isTeamModeEnabled()) {
    // Team-based sync is active
}

// Check if two players share inventory
Player player1 = ...;
Player player2 = ...;
if (api.isInSameGroup(player1, player2)) {
    // They share the same inventory
}

// Get team ID (null if not in team mode or no team)
UUID teamId = api.getTeamId(player);
```

#### Reload Plugin
```java
api.reload();
```

### Example: Custom Death Handler

```java
import net.opmasterleo.multiinvsync.api.MultiInvSyncAPI;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class CustomDeathHandler implements Listener {
    
    private final MultiInvSyncAPI api;
    
    public CustomDeathHandler(MultiInvSyncAPI api) {
        this.api = api;
    }
    
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        
        // Custom logic here...
        
        // Clear all synchronized inventories (except the dead player)
        api.clearAllInventories(player, false);
    }
}
```

### Example: Temporary Bypass

```java
import net.opmasterleo.multiinvsync.api.MultiInvSyncAPI;

public void giveItemWithoutSync(Player player, ItemStack item) {
    MultiInvSyncAPI api = MultiInvSyncPlugin.getInstance().getAPI();
    
    // Temporarily bypass sync
    api.addBypass(player.getUniqueId());
    
    // Give item
    player.getInventory().addItem(item);
    
    // Remove bypass
    api.removeBypass(player.getUniqueId());
}
```

### Maven/Gradle Dependency

Add to your `build.gradle.kts`:

```kotlin
repositories {
    maven("https://jitpack.io") // If using JitPack
}

dependencies {
    compileOnly("com.github.yourusername:MultiPlayerInvSync:1.0.0")
}
```

Add to your `plugin.yml`:

```yaml
depend: [MultiPlayerInvSync]
```

## 🎯 Usage Examples

### Global Inventory Sync
Perfect for shared survival challenges where all players share one inventory:

```yaml
enabled: true
sync:
  main-inventory: true
  armor: true
  offhand: true
  ender-chest: true
teams:
  enabled: false
death:
  shared-death: true
```

### Team-Based Sync
Great for team-vs-team gameplay:

```yaml
enabled: true
teams:
  enabled: true
  plugin: "betterteams"
  fallback-to-global: false
death:
  shared-death: true
```

### Hardcore Mode
Enable shared death to make the game more challenging:

```yaml
death:
  shared-death: true  # All team members lose items when one dies
  broadcast-shared-death: true
```

## 🔧 Death Behavior

### How It Works

1. **Player Dies**: One player in the sync group dies
2. **Items Drop**: Only the dead player's items drop (prevents duping)
3. **Others Cleared**: All other players in the sync group have their inventories cleared
4. **KeepInventory**: If keepInventory is true, nothing happens
5. **Broadcast**: Optional death message to affected players

### Why This Design?

- **Prevents Duping**: Items only drop once from the dead player
- **Fair Gameplay**: Others lose items but don't get extra drops
- **Respects Game Rules**: Works with keepInventory
- **Team Punishment**: Encourages team coordination

## 🚀 Performance

The plugin is optimized for maximum performance:

- **NMS**: Direct access to Minecraft internals bypasses Bukkit overhead
- **Netty**: Packet-level interception catches ALL inventory changes
- **Async**: Heavy operations run asynchronously
- **Smart Caching**: Reduces redundant sync operations
- **No Configuration Needed**: Performance features are always on

### Expected Performance
- **Sync Latency**: < 50ms
- **TPS Impact**: < 0.1 TPS with 50+ players
- **Memory**: ~2MB per 100 players

## 📖 Team Plugin Integration

### Currently Supported
- **BetterTeams**: Full integration

### Adding New Team Plugins

Create a provider class:

```java
package net.opmasterleo.multiinvsync.team.providers;

import net.opmasterleo.multiinvsync.team.TeamProvider;
import org.bukkit.entity.Player;
import java.util.Collection;
import java.util.UUID;

public class YourTeamProvider implements TeamProvider {
    
    @Override
    public String getName() {
        return "YourPlugin";
    }
    
    @Override
    public boolean isInTeam(Player player) {
        // Check if player is in a team
    }
    
    @Override
    public UUID getTeamId(Player player) {
        // Return team UUID
    }
    
    @Override
    public Collection<Player> getTeamMembers(Player player) {
        // Return team members
    }
    
    @Override
    public boolean isAvailable() {
        // Check if plugin is installed
    }
}
```

Register in `TeamManager.initialize()`:

```java
availableProviders.add(new YourTeamProvider());
```

## 🐛 Troubleshooting

### Inventories not syncing
1. Check `/mis info` - ensure plugin is enabled
2. Check for bypass: `/mis bypass off`
3. Enable debug mode in config.yml
4. Check console for errors

### Team sync not working
1. Verify team plugin is installed
2. Check `teams.enabled: true`
3. Verify players are in same team
4. Check `/mis info` for team provider

## 📄 License

MIT License - See [LICENSE](LICENSE) file

## 🤝 Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## 💬 Support

- **Wiki**: [Documentation](https://github.com/opmasterleo/MultiPlayerInvSync/wiki)

## 📊 Version Compatibility

| Plugin | Minecraft | Java | Paper |
|--------|-----------|------|-------|
| 1.0.0  | 1.21.1+   | 21+  | Required |

## 🎉 Credits

- **Author**: OPMasterLEO
- **Inspired by**: Community requests for global inventory sync

---

**Made with ❤️ for the Minecraft community**
