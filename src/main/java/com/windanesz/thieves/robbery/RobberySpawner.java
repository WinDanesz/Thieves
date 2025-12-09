package com.windanesz.thieves.robbery;

import com.windanesz.thieves.Settings;
import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.Utils;
import com.windanesz.thieves.capability.PlayerCapability;
import com.windanesz.thieves.entity.EntityMasterThief;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.entity.EntityThiefScout;
import com.windanesz.thieves.init.ModBlocks;
import com.windanesz.thieves.util.PlayerBaseDetector;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Handles spawning of robbery events including thieves and loot bags.
 */
public class RobberySpawner {

	private static final Random RANDOM = new Random();

	/**
	 * Triggers a full robbery event at the player's base.
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

		// Force-load chunks around base
		List<ChunkPos> loadedChunks = forceLoadChunksAroundBase(world, baseLocation, 3);

		// Find chests at base
		List<BlockPos> chestLocations = PlayerBaseDetector.scanForChests(world, baseLocation, 3);

		if (chestLocations.isEmpty()) {
			Thieves.LOGGER.warn("No chests found at base location for player {}, aborting robbery", player.getName());
			unloadChunks(world, loadedChunks);
			return;
		}

		// Find spawn position for loot bag (near chests)
		BlockPos lootBagPos = findLootBagSpawnPos(world, chestLocations);

		if (lootBagPos == null) {
			Thieves.LOGGER.warn("Could not find valid loot bag spawn position, aborting robbery");
			unloadChunks(world, loadedChunks);
			return;
		}

		// Spawn loot bag
		world.setBlockState(lootBagPos, ModBlocks.LOOT_BAG.getDefaultState());
		Thieves.LOGGER.info("Spawned loot bag at {}", lootBagPos);

		// Calculate number of thieves to spawn
		int thiefCount = calculateThiefCount(cap.completedRobberies);
		boolean shouldSpawnMaster = shouldSpawnMasterThief(cap.completedRobberies);

		Thieves.LOGGER.info("Spawning {} thieves (master: {})", thiefCount, shouldSpawnMaster);

		// Spawn thieves around loot bag
		int spawnedCount = 0;
		for (int i = 0; i < thiefCount; i++) {
			boolean isMaster = shouldSpawnMaster && i == 0; // First thief is master if applicable
			EntityThief thief = spawnThief(world, lootBagPos, chestLocations, isMaster);
			if (thief != null) {
				spawnedCount++;
			}
		}

		Thieves.LOGGER.info("Successfully spawned {} thieves for robbery", spawnedCount);

		// Mark robbery as completed
		cap.completeRobbery(world);

		// Unload chunks after a delay (let thieves start their work)
		// Note: In production, you'd want a more sophisticated chunk management system
		// For now, we'll rely on natural chunk loading/unloading
	}

	/**
	 * Spawns a scout thief to warn the player before a robbery.
	 */
	public static void spawnScout(EntityPlayer player, PlayerCapability cap) {
		World world = player.world;

		if (world.isRemote) {
			return;
		}

		// Find spawn position near player (32-64 blocks away)
		BlockPos spawnPos = findScoutSpawnPos(world, player.getPosition());

		if (spawnPos == null) {
			Thieves.LOGGER.warn("Could not find valid scout spawn position");
			return;
		}

		// Spawn scout
		EntityThiefScout scout = new EntityThiefScout(world);
		scout.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
		world.spawnEntity(scout);

		// Mark scout as spawned
		cap.markScoutSpawned(world);

		Thieves.LOGGER.info("Spawned scout for player {} at {}", player.getName(), spawnPos);
	}

	/**
	 * Checks if it's currently night time in the world.
	 */
	public static boolean isNightTime(World world) {
		long dayTime = world.getWorldTime() % 24000;
		return dayTime >= 12542 && dayTime <= 23458;
	}

	/**
	 * Spawns a thief (regular or master) near the loot bag.
	 */
	private static EntityThief spawnThief(World world, BlockPos lootBagPos, List<BlockPos> chestLocations, boolean isMaster) {
		// Find spawn position 8-16 blocks from loot bag
		BlockPos spawnPos = findThiefSpawnPos(world, lootBagPos, 8, 16);

		if (spawnPos == null) {
			Thieves.LOGGER.warn("Could not find valid thief spawn position near {}", lootBagPos);
			return null;
		}

		// Create thief entity
		EntityThief thief;
		if (isMaster) {
			thief = new EntityMasterThief(world);
		} else {
			thief = new EntityThief(world);
		}

		thief.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
		
		// Store loot bag and chest positions in thief's NBT for AI tasks
		// (This would require adding fields to EntityThief or using a capability)

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
	 * Finds a suitable position to spawn the loot bag near chests.
	 */
	private static BlockPos findLootBagSpawnPos(World world, List<BlockPos> chestLocations) {
		if (chestLocations.isEmpty()) {
			return null;
		}

		// Pick a random chest as reference
		BlockPos referenceChest = chestLocations.get(RANDOM.nextInt(chestLocations.size()));

		// Try to find a valid position 5-16 blocks from the chest
		for (int attempt = 0; attempt < 50; attempt++) {
			int offsetX = 5 + RANDOM.nextInt(12);
			int offsetZ = 5 + RANDOM.nextInt(12);

			if (RANDOM.nextBoolean()) offsetX = -offsetX;
			if (RANDOM.nextBoolean()) offsetZ = -offsetZ;

			BlockPos candidatePos = referenceChest.add(offsetX, 0, offsetZ);
			
			// Find ground level
			candidatePos = world.getHeight(candidatePos);

			// Check if position is valid (air block with solid ground)
			if (world.isAirBlock(candidatePos) && 
				world.getBlockState(candidatePos.down()).isSideSolid(world, candidatePos.down(), net.minecraft.util.EnumFacing.UP)) {
				return candidatePos;
			}
		}

		// Fallback: place right next to chest
		return Utils.findNearbyAirSpace(world, referenceChest, 5);
	}

	/**
	 * Finds a position to spawn a scout near the player.
	 */
	private static BlockPos findScoutSpawnPos(World world, BlockPos playerPos) {
		// Try to spawn 32-64 blocks away
		for (int attempt = 0; attempt < 50; attempt++) {
			int distance = 32 + RANDOM.nextInt(33); // 32-64 blocks
			double angle = RANDOM.nextDouble() * Math.PI * 2;

			int offsetX = (int) (Math.cos(angle) * distance);
			int offsetZ = (int) (Math.sin(angle) * distance);

			BlockPos candidatePos = playerPos.add(offsetX, 0, offsetZ);
			candidatePos = world.getHeight(candidatePos);

			// Check if valid spawn location
			if (isValidSpawnLocation(world, candidatePos)) {
				return candidatePos;
			}
		}

		return null;
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

	/**
	 * Force-loads chunks around the base location.
	 */
	private static List<ChunkPos> forceLoadChunksAroundBase(World world, BlockPos baseLocation, int chunkRadius) {
		List<ChunkPos> loadedChunks = new ArrayList<>();
		ChunkPos centerChunk = new ChunkPos(baseLocation);

		for (int x = centerChunk.x - chunkRadius; x <= centerChunk.x + chunkRadius; x++) {
			for (int z = centerChunk.z - chunkRadius; z <= centerChunk.z + chunkRadius; z++) {
				ChunkPos chunkPos = new ChunkPos(x, z);
				
				// Ensure chunk is loaded
				if (!world.isChunkGeneratedAt(x, z)) {
					world.getChunk(x, z);
				}

				loadedChunks.add(chunkPos);
			}
		}

		return loadedChunks;
	}

	/**
	 * Unloads chunks that were force-loaded (optional cleanup).
	 */
	private static void unloadChunks(World world, List<ChunkPos> chunks) {
		// In Minecraft 1.12.2, chunk unloading is managed by the game
		// This is a placeholder for potential future implementation
		// that might use ForgeChunkManager for persistent chunk loading
	}
}
