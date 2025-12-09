# Thieves Mod - Base Detection & Dynamic Robbery System
## Implementation Plan

**Version:** 1.0  
**Date:** December 9, 2025  
**Minecraft Version:** 1.12.2 Forge

---

## Overview

This document outlines the comprehensive implementation plan for the Thieves mod's core robbery mechanics. The system implements intelligent player base detection, progressive theft buildup influenced by player wealth, night-only robberies with advance scout warnings, and sophisticated thief AI for stealing items through a loot bag system.

---

## Core Concepts

### 1. Player Base Detection
- Uses multiple indicators: spawn point/bed location, chunk visit frequency, chest density
- Requires minimum 10 visits to chunk cluster over 30+ minutes playtime
- 70%+ confidence threshold before base is confirmed
- Periodic re-evaluation every 10 minutes to adapt to player movement
- Scans 5x5 chunk radius around detected base location

### 2. Robbery Progress System
- 0-100 float value stored in player capability
- Base buildup rate: 0.05-0.5 per minute based on chest value at base
- Higher value chests = faster progress buildup
- Resets to 0 after successful robbery
- 7-14 day cooldown period between robberies

### 3. Night-time Robberies Only
- Robberies trigger only during night (world time 12542-23458)
- Scout thieves appear 1-3 in-game days before full robbery
- Multiple scout visits (2-4) with increasing frequency as robbery approaches
- Scout behavior: observe player, flee if approached/attacked, despawn after 5-10 minutes

### 4. Difficulty Scaling
- Thief count: 1 + (completedRobberies / 3), capped at 5
- Master Thief variant: 10% chance after 5+ robberies
- Master Thief stats: 30 HP, 0.25 speed, carries 16-32 items vs regular 8-16

### 5. Loot Bag System
- TileEntity container with 27 slots (chest capacity)
- No GUI - right-click adds items, sneak+right-click removes random stack
- Spawns near player's chests during robbery
- Thieves transfer items from chests to bag
- When full, thief picks up bag and escapes
- If thief despawns far from players (>128 blocks), bag goes to persistent stash

### 6. Thief Stash Persistence
- WorldSavedData tracks stash locations per dimension
- Hidden underground structures with chest
- One active stash per base location (prevents clutter)
- Players can discover stashes to recover stolen items
- Optional: Rare map items hint at stash locations

### 7. Future Defense Systems
- `canThiefAccess(TileEntity)` interface for protected containers
- NBT tag support for "protected" chests
- Event hooks for addons: `ThiefRobberyStartEvent`, `ThiefStealItemEvent`
- Config options for defense blocks/items (placeholder for future expansion)

---

## Implementation Steps

### Step 1: Expand PlayerCapability
**File:** `src/main/java/com/windanesz/thieves/capability/PlayerCapability.java`

**New Fields:**
```java
// Robbery tracking
public float robberyProgress = 0.0F;              // 0-100, triggers robbery at 100
public int completedRobberies = 0;                // For difficulty scaling
public long lastRobberyTime = 0L;                 // World time of last robbery
public boolean scoutWarningActive = false;        // Scout currently active
public long lastScoutSpawnTime = 0L;              // When last scout appeared
public int scoutVisitCount = 0;                   // Number of scout visits this cycle

// Base detection
public BlockPos baseLocation = null;              // Detected base position
public int baseDimension = 0;                     // Base dimension ID
public Map<Long, Integer> chunkVisits = new HashMap<>();  // ChunkPos hash -> visit count
public Map<Long, Long> chunkVisitTimestamps = new HashMap<>();  // ChunkPos hash -> first visit time
public int chestCountAtBase = 0;                  // Number of chests at base
public float chestValueScore = 0.0F;              // Total value of items in base chests
public long lastBaseDetectionTime = 0L;           // Last time base was recalculated
```

**New Methods:**
- `addChunkVisit(ChunkPos pos, long worldTime)` - Records player visit to chunk
- `getChunkVisitCount(ChunkPos pos)` - Returns visit count for chunk
- `incrementRobberyProgress(float amount)` - Adds to progress, syncs to client
- `resetRobberyProgress()` - Resets progress after robbery
- `canTriggerRobbery(World world)` - Checks all conditions for robbery
- `spawnScoutWarning(EntityPlayer player)` - Triggers scout spawn

**NBT Serialization:**
- Serialize all new fields
- Handle BlockPos null safety
- Serialize Maps as NBT tag lists
- Backward compatibility with existing saves

**Client Sync:**
- Update `PacketPlayerSync` to include new fields
- Sync on: progress change, base detection, scout spawn, robbery trigger

---

### Step 2: Create PlayerBaseDetector
**File:** `src/main/java/com/windanesz/thieves/util/PlayerBaseDetector.java`

**Methods:**
```java
public static BlockPos detectPlayerBase(EntityPlayer player, PlayerCapability cap)
public static float calculateBaseConfidence(EntityPlayer player, BlockPos candidate, PlayerCapability cap)
public static BlockPos getSpawnOrBedLocation(EntityPlayer player)
public static ChunkPos getMostVisitedChunk(PlayerCapability cap)
public static List<BlockPos> scanForChests(World world, BlockPos center, int radius)
public static float calculateChestValue(World world, List<BlockPos> chestLocations)
public static float getItemValue(ItemStack stack)  // Uses config-based value table
public static void updateBaseDetection(EntityPlayer player, PlayerCapability cap)
```

**Detection Algorithm:**
1. Get spawn point/bed location (primary indicator)
2. Analyze chunk visit frequency (minimum 10 visits, 30+ min playtime)
3. For top 5 visited chunks, scan 5x5 chunk radius for chests
4. Calculate confidence score:
   - Spawn/bed in area: +40%
   - High visit frequency: +30%
   - Chest density (5+ chests): +20%
   - Time spent in area: +10%
5. If confidence >= 70%, set as base location
6. Cache chest locations and value scores

**Tick Handler Integration:**
- Run `updateBaseDetection()` every 12,000 ticks (10 minutes)
- Track player chunk position every 20 ticks
- Increment chunk visit counters

---

### Step 3: Implement BlockLootBag & TileEntityLootBag
**Files:** 
- `src/main/java/com/windanesz/thieves/block/BlockLootBag.java`
- `src/main/java/com/windanesz/thieves/tileentity/TileEntityLootBag.java`
- `src/main/java/com/windanesz/thieves/item/ItemLootBag.java` (new)

**BlockLootBag Features:**
- Extends `Block`, has TileEntity
- Bounding box: 0.75x0.75x0.75 (smaller than full block)
- No GUI on right-click
- Right-click: adds held item to bag inventory
- Sneak + right-click: removes random stack from bag
- Visual: Particle effects when full
- `isReadyForPickup()` - returns true when full (27 slots occupied)
- Drops contents when broken by player
- Cannot be broken by thieves, only picked up when full

**TileEntityLootBag:**
```java
public class TileEntityLootBag extends TileEntity {
    private ItemStackHandler inventory = new ItemStackHandler(27);
    
    public boolean addItem(ItemStack stack);
    public ItemStack removeRandomItem();
    public boolean isFull();
    public int getOccupiedSlots();
    public ItemStackHandler getInventory();
    
    // NBT serialization
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound);
    @Override
    public void readFromNBT(NBTTagCompound compound);
}
```

**ItemLootBag:**
- Carried by thieves when picked up
- Right-click to place back in world
- Stores all 27 slots in NBT
- Special rendering (maybe textured bundle/sack)

---

### Step 4: Create EntityThiefScout & EntityMasterThief
**Files:**
- `src/main/java/com/windanesz/thieves/entity/EntityThiefScout.java`
- `src/main/java/com/windanesz/thieves/entity/EntityMasterThief.java`

**EntityThiefScout (extends EntityThief):**
```java
// Special AI tasks
- ThiefScoutAIObserve: Watch player from distance (16-32 blocks)
- ThiefScoutAIFlee: Flee when player within 8 blocks or attacked
- Remove attack/steal tasks from parent

// Attributes
- Health: 15.0
- Speed: 0.25 (faster than regular)
- Follow range: 48.0 (watches from distance)

// Behavior
- Spawns 1-3 days before robbery
- Watches player for 5-10 minutes
- Grants advancement "Being Watched" when first spotted
- Subtle particle effects (eyes glowing in dark?)
- Despawns after observation period
- If killed: delays robbery by 3-7 days
```

**EntityMasterThief (extends EntityThief):**
```java
// Enhanced attributes
- Health: 30.0 (vs 20.0)
- Speed: 0.25 (vs 0.2)
- Attack damage: 5.0 (vs 3.0)
- Follow range: 40.0 (vs 32.0)

// Enhanced AI
- Carries 16-32 items (vs 8-16)
- Prioritizes valuable items
- Faster stealing (1.5x speed)
- Better pathfinding (can break blocks?)
- Wears leather armor for distinction

// Spawn conditions
- 10% chance when spawning thieves
- Only after player has experienced 5+ robberies
- Max 1 per robbery event
```

---

### Step 5: Implement RobberySpawner System
**File:** `src/main/java/com/windanesz/thieves/robbery/RobberySpawner.java`

**Core Methods:**
```java
public static void triggerRobbery(EntityPlayer player, PlayerCapability cap)
public static void spawnScout(EntityPlayer player, PlayerCapability cap)
public static boolean isNightTime(World world)
public static List<BlockPos> getChestLocations(World world, BlockPos base)
public static BlockPos findLootBagSpawnPos(World world, List<BlockPos> chests)
public static int calculateThiefCount(int completedRobberies)
public static boolean shouldSpawnMasterThief(int completedRobberies, Random rand)
```

**Robbery Trigger Logic:**
1. Check `cap.robberyProgress >= 100.0F`
2. Verify night time (12542-23458)
3. Check cooldown period elapsed (7-14 days since last robbery)
4. Confirm base location is valid
5. Force-load base chunk + 3x3 surrounding (using Forge ticket)
6. Scan for chests at base location
7. Spawn loot bag within 5-16 blocks of nearest chest
8. Calculate thief count: `1 + (completedRobberies / 3)`, max 5
9. Roll for master thief if eligible
10. Spawn thieves in 8-16 block radius around loot bag
11. Set AI targets to loot bag and nearby chests
12. Reset progress to 0, increment completedRobberies
13. Set cooldown timer

**Scout Spawn Logic:**
1. Check `cap.robberyProgress >= 75.0F` (warning threshold)
2. Check time since last scout (minimum 1 day between scouts)
3. Spawn scout within 32-64 blocks of player
4. Set `scoutWarningActive = true`
5. Scout observes for 5-10 minutes then despawns
6. Increment `scoutVisitCount`

---

### Step 6: Create Stealing AI Tasks
**Files:**
- `src/main/java/com/windanesz/thieves/entity/ai/ThiefAIStealToLootBag.java`
- `src/main/java/com/windanesz/thieves/entity/ai/ThiefAIPickupLootBag.java`
- `src/main/java/com/windanesz/thieves/entity/ai/ThiefAIEscapeWithLoot.java`

**ThiefAIStealToLootBag:**
```java
// State machine: SEEK_CHEST -> OPEN_CHEST -> TAKE_ITEMS -> NAVIGATE_TO_BAG -> DEPOSIT
private enum State { IDLE, SEEK_CHEST, EXTRACT_ITEMS, NAVIGATE_TO_BAG, DEPOSIT_ITEMS }

public boolean shouldExecute() {
    // Has loot bag target && bag not full && chests available
}

private BlockPos findNearestAccessibleChest() {
    // Scan 32 block radius
    // Skip protected chests (NBT tag or special TE interface)
    // Prefer chests with valuable items
}

private void extractItemsFromChest() {
    // Open chest (animation)
    // Select random occupied slot
    // Extract 1-8 items (or full stack if less)
    // Store in thief inventory temporarily
    // Render items in hand
}

private void depositItemsInBag() {
    // Navigate to bag
    // Play deposit animation
    // Transfer from thief inventory to bag TE
    // Clear hand rendering
}
```

**ThiefAIPickupLootBag:**
```java
public boolean shouldExecute() {
    // Loot bag exists && is full && thief inventory has space
}

public void updateTask() {
    // Navigate to loot bag
    // Convert block to ItemLootBag
    // Add item to thief inventory
    // Play pickup animation/sound
    // Transition to escape AI
}
```

**ThiefAIEscapeWithLoot:**
```java
public boolean shouldExecute() {
    // Has ItemLootBag in inventory
}

public void updateTask() {
    // Set speed multiplier to 1.5x
    // Navigate away from base location
    // Prefer darker areas (lower light level)
    // Avoid players (flee from nearby players)
    // Check escape conditions:
    //   - Distance from base > 200 blocks
    //   - Time elapsed > 10 minutes
    //   - No players within 128 blocks
    // When conditions met: create stash and despawn
}
```

---

### Step 7: Implement ThiefStashManager
**File:** `src/main/java/com/windanesz/thieves/world/ThiefStashManager.java`

**WorldSavedData Implementation:**
```java
public class ThiefStashManager extends WorldSavedData {
    private static final String DATA_NAME = "thieves_stash_manager";
    
    // Per-dimension stash tracking
    private Map<Integer, List<StashData>> stashesByDimension = new HashMap<>();
    
    public static ThiefStashManager get(World world);
    
    public BlockPos createStash(World world, BlockPos nearLocation, ItemStackHandler lootContents);
    public boolean hasStashNearLocation(World world, BlockPos location, int radius);
    public List<StashData> getStashesInDimension(int dimensionId);
    public void removeStash(World world, BlockPos location);
    
    @Override
    public void readFromNBT(NBTTagCompound nbt);
    
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound);
    
    public static class StashData {
        public BlockPos position;
        public long createdTime;
        public int dimensionId;
        public BlockPos associatedBase;  // Which player base this came from
    }
}
```

**Stash Generation:**
1. Find suitable location 50-150 blocks from escape point
2. Prefer underground (Y: 30-50) or hidden in caves
3. Generate small structure:
   - 3x3x3 room with cobblestone/stone bricks
   - Chest in center containing loot
   - Maybe decorative elements (cracked blocks, torches)
   - Spawn protection (no mob spawns nearby)
4. Store location in WorldSavedData
5. Limit 1 active stash per player base (replace old if exists)

**Discovery Mechanic:**
- Optional: Drop rare "Thief's Map Fragment" during robbery
- Map leads toward stash location (treasure map style)
- Breaking stash chest plays sound, particles
- Achievement for finding first stash

---

### Step 8: Add Configuration Options
**File:** `src/main/java/com/windanesz/thieves/Settings.java`

**New Config Categories:**

```java
@Config.Comment("Robbery system settings")
public static RobberySettings robbery = new RobberySettings();

public static class RobberySettings {
    @Config.Comment("Base progress buildup rate per minute")
    public float baseProgressRate = 0.05F;
    
    @Config.Comment("Maximum progress buildup rate per minute (with high-value base)")
    public float maxProgressRate = 0.5F;
    
    @Config.Comment("Progress threshold to trigger robbery")
    public float robberyThreshold = 100.0F;
    
    @Config.Comment("Minimum cooldown between robberies (days)")
    public int minCooldownDays = 7;
    
    @Config.Comment("Maximum cooldown between robberies (days)")
    public int maxCooldownDays = 14;
    
    @Config.Comment("Enable night-only robberies")
    public boolean nightOnly = true;
    
    @Config.Comment("Enable scout warnings before robberies")
    public boolean enableScouts = true;
    
    @Config.Comment("Days before robbery that scouts appear (min)")
    public int minScoutWarningDays = 1;
    
    @Config.Comment("Days before robbery that scouts appear (max)")
    public int maxScoutWarningDays = 3;
    
    @Config.Comment("Enable master thief variant")
    public boolean enableMasterThief = true;
    
    @Config.Comment("Master thief spawn chance (0.0-1.0)")
    public float masterThiefChance = 0.1F;
    
    @Config.Comment("Robberies required before master thief can spawn")
    public int masterThiefMinRobberies = 5;
}

@Config.Comment("Base detection settings")
public static BaseDetectionSettings baseDetection = new BaseDetectionSettings();

public static class BaseDetectionSettings {
    @Config.Comment("Minimum chunk visits to consider for base")
    public int minChunkVisits = 10;
    
    @Config.Comment("Minimum playtime in area (minutes)")
    public int minTimeInArea = 30;
    
    @Config.Comment("Confidence threshold to confirm base (0.0-1.0)")
    public float confidenceThreshold = 0.7F;
    
    @Config.Comment("Base detection update frequency (minutes)")
    public int updateFrequencyMinutes = 10;
    
    @Config.Comment("Chunk scan radius around base candidate")
    public int scanRadius = 5;
    
    @Config.Comment("Minimum chests required to confirm base")
    public int minChestsForBase = 3;
}

@Config.Comment("Item value scores for robbery progress calculation")
public static ItemValueSettings itemValues = new ItemValueSettings();

public static class ItemValueSettings {
    @Config.Comment("Value score for diamond blocks")
    public float diamondBlock = 10.0F;
    
    @Config.Comment("Value score for diamond items")
    public float diamond = 5.0F;
    
    @Config.Comment("Value score for gold blocks")
    public float goldBlock = 5.0F;
    
    @Config.Comment("Value score for gold items")
    public float gold = 2.0F;
    
    @Config.Comment("Value score for iron blocks")
    public float ironBlock = 2.0F;
    
    @Config.Comment("Value score for iron items")
    public float iron = 1.0F;
    
    @Config.Comment("Value multiplier for enchanted items")
    public float enchantedMultiplier = 2.0F;
    
    // ... additional item values
}

@Config.Comment("Loot bag settings")
public static LootBagSettings lootBag = new LootBagSettings();

public static class LootBagSettings {
    @Config.Comment("Loot bag inventory size")
    public int inventorySize = 27;
    
    @Config.Comment("Loot bag durability (hits before breaking)")
    public int durability = 5;
    
    @Config.Comment("Should loot bag spill contents when broken")
    public boolean spillOnBreak = true;
}
```

---

### Step 9: Event Handlers & Tick System
**File:** `src/main/java/com/windanesz/thieves/event/RobberyEventHandler.java` (new)

**Event Subscriptions:**
```java
@SubscribeEvent
public void onPlayerTick(TickEvent.PlayerTickEvent event) {
    // Server-side only, End phase only
    if (event.phase != TickEvent.Phase.END || event.player.world.isRemote) return;
    
    EntityPlayer player = event.player;
    PlayerCapability cap = PlayerCapability.get(player);
    
    // Every 20 ticks (1 second)
    if (player.ticksExisted % 20 == 0) {
        // Track chunk visits
        ChunkPos currentChunk = new ChunkPos(player.getPosition());
        cap.addChunkVisit(currentChunk, player.world.getTotalWorldTime());
        
        // Update robbery progress
        if (cap.baseLocation != null) {
            float progressIncrease = calculateProgressIncrease(cap);
            cap.incrementRobberyProgress(progressIncrease / 20.0F); // Per-tick amount
        }
    }
    
    // Every 12000 ticks (10 minutes)
    if (player.ticksExisted % 12000 == 0) {
        PlayerBaseDetector.updateBaseDetection(player, cap);
    }
    
    // Check for robbery trigger
    if (cap.canTriggerRobbery(player.world)) {
        RobberySpawner.triggerRobbery(player, cap);
    }
    
    // Check for scout spawn
    if (cap.shouldSpawnScout(player.world)) {
        RobberySpawner.spawnScout(player, cap);
    }
}

private float calculateProgressIncrease(PlayerCapability cap) {
    float baseRate = Settings.robbery.baseProgressRate;
    float maxRate = Settings.robbery.maxProgressRate;
    float valueScore = cap.chestValueScore;
    
    // Scale progress based on chest value (0-100 value score range)
    float scaledRate = baseRate + ((maxRate - baseRate) * Math.min(valueScore / 100.0F, 1.0F));
    return scaledRate / 60.0F; // Convert per-minute to per-second
}
```

**Custom Events for Addons:**
```java
// Event fired when robbery is about to start (cancellable)
public class ThiefRobberyStartEvent extends Event {
    private final EntityPlayer player;
    private final BlockPos baseLocation;
    private int thiefCount;
    
    @Cancelable
    public boolean isCancelable() { return true; }
    
    // Getters and setters
}

// Event fired when thief steals an item (cancellable)
public class ThiefStealItemEvent extends Event {
    private final EntityThief thief;
    private final BlockPos chestPos;
    private final ItemStack stack;
    
    @Cancelable
    public boolean isCancelable() { return true; }
    
    // Getters and setters
}
```

---

### Step 10: Registration & Integration

**ModBlocks.java:**
- Register `BlockLootBag`
- Register `TileEntityLootBag`

**ModItems.java:**
- Register `ItemLootBag`

**ModEntities.java:**
- Register `EntityThiefScout`
- Register `EntityMasterThief`
- Add spawn eggs

**Thieves.java (main class):**
- Register `RobberyEventHandler`
- Initialize `ThiefStashManager` on world load

**Client Registration:**
- Renderers for new entities
- Model for loot bag block/item
- Particle effects

---

## Testing Checklist

### Base Detection
- [x] Player spawn point is detected as base
- [x] Bed location is detected as base
- [x] High-traffic chunks with chests are detected
- [x] Base detection updates when player moves base
- [x] Confidence threshold works correctly
- [x] Chest value calculation is accurate

### Progress System
- [x] Progress increments based on chest value
- [x] Progress syncs to client correctly
- [x] Progress resets after robbery
- [x] Cooldown period prevents immediate re-robbery
- [ ] Command `/getrobbery progress` shows current value

### Scout System
- [x] Scouts spawn 1-3 days before robbery
- [x] Scouts observe player from distance
- [x] Scouts flee when approached
- [x] Scouts despawn after timer
- [ ] Killing scout delays robbery
- [x] Advancement granted when spotted

### Robbery Trigger
- [x] Only triggers at night
- [x] Requires progress >= 100
- [x] Respects cooldown period
- [x] Force-loads chunks correctly
- [x] Spawns correct number of thieves
- [x] Master thief spawns after 5+ robberies

### Loot Bag
- [x] Can add items with right-click
- [x] Can remove items with sneak+right-click
- [ ] Shows full state visually
- [x] Thieves can pick up when full
- [x] Converts to item correctly
- [x] Drops contents when broken

### Stealing AI
- [x] Thieves pathfind to chests
- [x] Thieves extract items correctly (1-8 items)
- [ ] Items render in thief's hand
- [x] Thieves navigate to loot bag
- [x] Items deposit into bag
- [x] Process repeats until bag full

### Escape System
- [x] Thief picks up full bag
- [x] Thief moves away from base
- [x] Thief prefers darker areas
- [x] Thief despawns at correct distance/time
- [x] Stash is created on despawn
- [x] Loot transfers to stash chest

### Stash System
- [x] Stash generates in valid location
- [x] Stash structure generates correctly
- [x] Chest contains stolen items
- [x] WorldSavedData persists across restarts
- [x] Only 1 stash per base location
- [x] Players can find and loot stashes

### Configuration
- [x] All config options work correctly
- [x] Config changes take effect
- [ ] Default values are balanced
- [x] Config file generates properly

---

## Future Expansion Hooks

### Defense Systems (Future)
- `IThiefProtectable` interface for containers
- `canThiefAccess(EntityThief)` method
- Protected chest blocks
- Guard entity system
- Alarm/trap blocks
- Magical wards

### Difficulty Modifiers (Future)
- Moon phase affects robbery chance
- Biome-specific thief variants
- Weather conditions (rain = more thieves?)
- Player wealth attracts different thief types
- Reputation system (more robberies = infamous)

### Player Tracking (Future)
- Thief guild reputation
- Bounty system for catching thieves
- Contracts to protect other players' bases
- Thief information broker NPCs

### Loot Recovery (Future)
- Treasure maps to stashes
- Thief interrogation mechanic
- Tracking dogs/entities
- Divination magic for finding stashes

---

## Known Limitations

1. **Chunk Loading**: Robbery system requires chunks to be loaded. If chunks unload during robbery, thieves may freeze.
   - **Solution**: Use Forge ticket system to keep chunks loaded during active robbery

2. **Multiplayer Base Sharing**: If multiple players share a base, only one player's capability tracks it.
   - **Solution**: Future enhancement to detect shared bases and split progress

3. **Dimension Travel**: If player moves dimensions, base detection resets.
   - **Solution**: Track bases per dimension in capability

4. **Performance**: Frequent chest scanning could lag on large bases.
   - **Solution**: Cache chest locations, only re-scan every 10 minutes

5. **Protected Containers**: Mod compatibility with other storage mods.
   - **Solution**: Event-based protection system allows addon compatibility

---

## Dependencies

- Minecraft Forge 1.12.2
- Java 8+
- No external mod dependencies (pure Forge)

---

## Credits

Implementation based on design concepts from:
- Player base detection: Inspired by raid mechanics
- Capability system: Adapted from Electroblob's Wizardry
- AI tasks: Built on Forge/Vanilla AI framework
- WorldSavedData: Standard Forge persistence pattern

---

**End of Implementation Plan**
