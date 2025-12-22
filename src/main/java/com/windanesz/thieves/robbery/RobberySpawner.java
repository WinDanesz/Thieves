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

		// Force-load chunks around base
		List<ChunkPos> loadedChunks = forceLoadChunksAroundBase(world, baseLocation, 3);

		// Find chests at base
		List<BlockPos> chestLocations = PlayerBaseDetector.scanForChests(world, baseLocation, 3);

		if (chestLocations.isEmpty()) {
			Thieves.LOGGER.warn("No chests found at base location for player {}, aborting robbery", player.getName());
			unloadChunks(world, loadedChunks);
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
				spawnedCount++;
				spawnedThieves.add(thief);
			}
		}

		Thieves.LOGGER.info("Successfully spawned {} thieves for robbery", spawnedCount);

		// Notify the player
		if (spawnedCount > 0) {
			net.minecraft.util.text.TextComponentString message = new net.minecraft.util.text.TextComponentString(
					net.minecraft.util.text.TextFormatting.RED + "Your base is being robbed by " +
					(spawnedCount > 1 ? spawnedCount + " thieves" : "a thief") + "!");
			player.sendMessage(message);
		}

		// --- AI chest pathfinding and loot bag placement logic ---
		// Shared state for callback
		class SharedLootBagState {
			boolean placed = false;
			BlockPos lootBagPos = null;
		}
		SharedLootBagState lootBagState = new SharedLootBagState();

		// Callback for when a thief reaches a chest
		com.windanesz.thieves.entity.ai.ThiefAISeekChestAndSignal.ChestReachedCallback callback = (thief, chestPos) -> {
			if (!lootBagState.placed) {
				// Place loot bag within configured distance of the chest
				int min = Settings.robbery.lootBagSpawnMinDistance;
				int max = Settings.robbery.lootBagSpawnMaxDistance;
				int dist = min + RANDOM.nextInt(max - min + 1);
				
				double angle = RANDOM.nextDouble() * Math.PI * 2;
				int offsetX = (int) (Math.cos(angle) * dist);
				int offsetZ = (int) (Math.sin(angle) * dist);
				BlockPos candidate = chestPos.add(offsetX, 0, offsetZ);
				BlockPos lootBagPos = world.getHeight(candidate);
				
				// Ensure valid placement (simplified check)
				if (!world.isAirBlock(lootBagPos)) {
					lootBagPos = Utils.findNearbyAirSpace(world, lootBagPos, 3);
				}

				world.setBlockState(lootBagPos, ModBlocks.LOOT_BAG.getDefaultState());
				lootBagState.placed = true;
				lootBagState.lootBagPos = lootBagPos;
				Thieves.LOGGER.info("Placed loot bag at {} after thief reached chest {}", lootBagPos, chestPos);
				
				// Notify all thieves of loot bag location (set a field and update their AI)
				for (EntityThief t : spawnedThieves) {
					t.setRobberyLootBagPos(lootBagPos);
				}
				// Mark robbery as completed
				cap.completeRobbery(world);
			}
		};

		// Assign the AI task to each thief
		for (EntityThief thief : spawnedThieves) {
			thief.tasks.addTask(0, new com.windanesz.thieves.entity.ai.ThiefAISeekChestAndSignal(thief, chestLocations, callback));
		}

		// Timeout check: schedule a check
		long timeoutTick = world.getTotalWorldTime() + 20 * Settings.robbery.robberyTimeout;
		net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new Object() {
			@net.minecraftforge.fml.common.eventhandler.SubscribeEvent
			public void onWorldTick(net.minecraftforge.fml.common.gameevent.TickEvent.WorldTickEvent event) {
				if (event.world != world) return;
				if (world.getTotalWorldTime() >= timeoutTick) {
					if (!lootBagState.placed) {
						// Despawn all thieves and notify player
						for (EntityThief thief : spawnedThieves) {
							thief.setDead();
						}
						player.sendMessage(new net.minecraft.util.text.TextComponentString(
							net.minecraft.util.text.TextFormatting.GRAY + "Robbery failed: Thieves could not reach a chest."));
						Thieves.LOGGER.info("Robbery failed: Thieves could not reach a chest in time.");
					}
					net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(this);
				}
				// If loot bag placed, unregister
				if (lootBagState.placed) {
					net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(this);
				}
			}
		});

		// Unload chunks after a delay (let thieves start their work)
		// Note: In production, you'd want a more sophisticated chunk management system
		// For now, we'll rely on natural chunk loading/unloading
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

		// Create thief entity
		EntityThief thief = new EntityThief(world);
		// Randomize skin immediately for robbery-spawned thieves
		thief.setSkinIndex(RANDOM.nextInt(EntityThief.SKIN_VARIATION_COUNT));

		// Slightly offset each thief to avoid exact overlap (optional, can be removed for single block)
		double offsetX = 0.5 + (RANDOM.nextDouble() - 0.5) * 0.2;
		double offsetZ = 0.5 + (RANDOM.nextDouble() - 0.5) * 0.2;
		thief.setPosition(spawnPos.getX() + offsetX, spawnPos.getY(), spawnPos.getZ() + offsetZ);

		// Mark as robbery thief for tracking
		thief.setRobberyThief(true);
		//thief.onInitialSpawn(world.getDifficultyForLocation(spawnPos, thief.data));

		// Equip weapon based on completed robberies count
		thief.equipWeaponBasedOnRaidCount(completedRobberies);

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

		// Try to find a valid position 2-6 blocks from the chest
		for (int attempt = 0; attempt < 50; attempt++) {
			int offsetX = 2 + RANDOM.nextInt(5);
			int offsetZ = 2 + RANDOM.nextInt(5);

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
