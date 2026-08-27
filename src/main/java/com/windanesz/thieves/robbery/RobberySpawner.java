package com.windanesz.thieves.robbery;

import com.windanesz.thieves.Settings;
import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.Utils;
import com.windanesz.thieves.capability.PlayerCapability;
import com.windanesz.thieves.entity.EntityMasterThief;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.entity.ai.ThiefAISeekChestAndSignal;
import com.windanesz.thieves.init.ModBlocks;
import com.windanesz.thieves.util.PlayerBaseDetector;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Handles spawning of robbery events including thieves and loot bags.
 */
public class RobberySpawner {

    // Store the cluster spawn position for the current robbery
    private static BlockPos clusterSpawnPos = null;

    private static final Random RANDOM = new Random();

    /**
     * Triggers a full robbery event at the player's base.
     * Flow:
     * 1. Spawns thieves first at a configured distance.
     * 2. Thieves attempt to find a path to a chest within a timeout (default 1 min).
     * 3. If a thief reaches a chest, a loot bag is placed nearby.
     * 4. All thieves then target the loot bag to steal items.
     * 5. If no thief reaches a chest within the timeout, the robbery fails and thieves despawn.
     */
    public static void triggerRobbery(EntityPlayer player, PlayerCapability cap) {
        World world = player.world;

        if (world.isRemote || !(world instanceof WorldServer)) {
            return;
        }

        BlockPos baseLocation = cap.baseLocation;
        if (baseLocation == null) {
            Thieves.LOGGER.warn("Attempted to trigger robbery but player {} has no detected base", player.getName());
            return;
        }

        Thieves.LOGGER.info("Triggering robbery for player {} at base location {}", player.getName(), baseLocation);

        // Force-load chunks around base via the centralized per-player manager
        RobberyChunkManager.startRobbery(player.getUniqueID(), world, baseLocation);

        // Find chests at base
        List<BlockPos> chestLocations = PlayerBaseDetector.scanForChests(world, baseLocation, 3);

        if (chestLocations.isEmpty()) {
            Thieves.LOGGER.warn("No chests found at base location for player {}, aborting robbery", player.getName());
            RobberyChunkManager.endRobbery(player.getUniqueID());
            return;
        }

        // Calculate number of thieves to spawn
        int thiefCount = calculateThiefCount(cap.completedRobberies);
        boolean shouldSpawnMaster = shouldSpawnMasterThief(cap.completedRobberies);

        Thieves.LOGGER.info("Spawning {} thieves (master: {})", thiefCount, shouldSpawnMaster);

        // Reset cluster spawn position for this robbery
        clusterSpawnPos = null;
        int spawnedCount = 0;
        List<EntityThief> spawnedThieves = new ArrayList<>();
        for (int i = 0; i < thiefCount; i++) {
            boolean isMaster = shouldSpawnMaster && i == 0; // First thief is master if applicable
            EntityThief thief = spawnThief(world, baseLocation, chestLocations, isMaster, cap.completedRobberies);
            if (thief != null) {
                thief.setRobberyTargetUUID(player.getUniqueID());
                spawnedCount++;
                spawnedThieves.add(thief);
            }
        }

        Thieves.LOGGER.info("Successfully spawned {} thieves for robbery", spawnedCount);

        if (spawnedCount == 0) {
            // No thieves could be spawned — abort cleanly
            RobberyChunkManager.endRobbery(player.getUniqueID());
            return;
        }

        // Stamp lastRobberyTime and reset progress NOW — before any world close can happen.
        // This prevents a re-trigger on reload if the world closes before the loot bag is placed.
        cap.startRobbery(world);

        // Notify the player
        String thiefWord = spawnedCount > 1 ? spawnedCount + " thieves" : "a thief";
        player.sendMessage(new TextComponentString(
                TextFormatting.RED + "Your base is being robbed by " + thiefWord + "!"));

        // Create the ActiveRobbery object to track this robbery
        long timeoutTick = world.getTotalWorldTime() + 20L * Settings.robbery.robberyTimeout;
        ActiveRobbery robbery = new ActiveRobbery(player.getUniqueID(), player, world, spawnedThieves, timeoutTick);

        // Callback for when a thief reaches a chest — sets state on the robbery object
        ThiefAISeekChestAndSignal.ChestReachedCallback callback = (thief, chestPos) -> {
            if (robbery.lootBagPlaced) return; // Another thief already placed it

            // Place loot bag within configured distance of the chest
            int min = Settings.robbery.lootBagSpawnMinDistance;
            int max = Settings.robbery.lootBagSpawnMaxDistance;
            int dist = min + RANDOM.nextInt(max - min + 1);

            double angle = RANDOM.nextDouble() * Math.PI * 2;
            int offsetX = (int) (Math.cos(angle) * dist);
            int offsetZ = (int) (Math.sin(angle) * dist);
            BlockPos candidate = chestPos.add(offsetX, 0, offsetZ);
            BlockPos lootBagPos = world.getHeight(candidate);

            // Ensure valid placement
            if (!world.isAirBlock(lootBagPos)) {
                BlockPos safePos = Utils.findNearbyAirSpace(world, lootBagPos, 3);
                if (safePos != null) {
                    lootBagPos = safePos;
                }
            }

            world.setBlockState(lootBagPos, ModBlocks.LOOT_BAG.getDefaultState());

            // Update the shared robbery state (Fix 2 — no more inner class SharedLootBagState)
            robbery.lootBagPlaced = true;
            robbery.lootBagPos = lootBagPos;

            Thieves.LOGGER.info("Placed loot bag at {} after thief reached chest {}", lootBagPos, chestPos);

            // Notify all thieves of the loot bag location
            for (EntityThief t : spawnedThieves) {
                t.setRobberyLootBagPos(lootBagPos);
            }

            // Mark robbery as completed in the player's capability
            cap.completeRobbery(world);
        };

        // Assign the AI task to each thief
        for (EntityThief thief : spawnedThieves) {
            thief.tasks.addTask(0, new ThiefAISeekChestAndSignal(thief, chestLocations, callback));
        }

        // Register with the central manager — no more anonymous event bus listeners (Fix 2)
        RobberyManager.register(robbery);
    }

    /**
     * Checks if it's currently night time in the world.
     */
    public static boolean isNightTime(World world) {
        long dayTime = world.getWorldTime() % 24000;
        return dayTime >= 12542 && dayTime <= 23458;
    }

    /**
     * Spawns a thief (regular or master) near the base.
     */
    private static EntityThief spawnThief(World world, BlockPos baseLocation, List<BlockPos> chestLocations, boolean isMaster, int completedRobberies) {
        // Use a configurable spawn distance
        int distance = Settings.robbery.thiefSpawnDistance;
        if (clusterSpawnPos == null) {
            // Find a valid cluster spawn position only once per robbery
            clusterSpawnPos = findThiefSpawnPos(world, baseLocation, distance, distance);
        }
        BlockPos spawnPos = clusterSpawnPos;
        if (spawnPos == null) {
            Thieves.LOGGER.warn("Could not find valid thief spawn position near {}", baseLocation);
            return null;
        }

        // Create thief entity (master or regular)
        EntityThief thief = isMaster ? new EntityMasterThief(world) : new EntityThief(world);

        // Randomize skin immediately for robbery-spawned thieves
        thief.setSkinIndex(RANDOM.nextInt(EntityThief.SKIN_VARIATION_COUNT));

        // Slightly offset each thief to avoid exact overlap
        double offsetX = 0.5 + (RANDOM.nextDouble() - 0.5) * 0.2;
        double offsetZ = 0.5 + (RANDOM.nextDouble() - 0.5) * 0.2;
        thief.setPosition(spawnPos.getX() + offsetX, spawnPos.getY(), spawnPos.getZ() + offsetZ);

        // Mark as robbery thief for tracking
        thief.setRobberyThief(true);

        // Equip weapon based on completed robberies count
        thief.equipWeaponBasedOnRaidCount(completedRobberies);

        world.spawnEntity(thief);

        return thief;
    }

    /**
     * Calculates how many thieves should spawn based on completed robbery count.
     */
    public static int calculateThiefCount(int completedRobberies) {
        int count = 1 + (completedRobberies / 3);
        return Math.min(count, 5); // Cap at 5 thieves
    }

    /**
     * Determines if a master thief should spawn.
     */
    public static boolean shouldSpawnMasterThief(int completedRobberies) {
        if (!Settings.robbery.enableMasterThief) {
            return false;
        }

        if (completedRobberies < Settings.robbery.masterThiefMinRobberies) {
            return false;
        }

        return RANDOM.nextFloat() < Settings.robbery.masterThiefChance;
    }

    /**
     * Finds a position to spawn a thief within a distance range.
     */
    private static BlockPos findThiefSpawnPos(World world, BlockPos center, int minDistance, int maxDistance) {
        for (int attempt = 0; attempt < 50; attempt++) {
            int distance = minDistance + RANDOM.nextInt(maxDistance - minDistance + 1);
            double angle = RANDOM.nextDouble() * Math.PI * 2;

            int offsetX = (int) (Math.cos(angle) * distance);
            int offsetZ = (int) (Math.sin(angle) * distance);

            BlockPos candidatePos = center.add(offsetX, 0, offsetZ);
            candidatePos = world.getHeight(candidatePos);

            if (isValidSpawnLocation(world, candidatePos)) {
                return candidatePos;
            }
        }

        return null;
    }

    /**
     * Checks if a position is valid for entity spawning.
     */
    private static boolean isValidSpawnLocation(World world, BlockPos pos) {
        // Check 2 blocks of air space above solid ground
        if (!world.isAirBlock(pos) || !world.isAirBlock(pos.up())) {
            return false;
        }

        BlockPos groundPos = pos.down();
        if (!world.getBlockState(groundPos).isSideSolid(world, groundPos, net.minecraft.util.EnumFacing.UP)) {
            return false;
        }

        // Don't spawn in water/lava
        if (world.getBlockState(pos).getMaterial().isLiquid()) {
            return false;
        }

        return true;
    }
}
