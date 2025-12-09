package com.windanesz.thieves.util;

import com.windanesz.thieves.Settings;
import com.windanesz.thieves.capability.PlayerCapability;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.*;

/**
 * Utility class for detecting the player's base location based on various indicators.
 * Uses spawn point/bed location, chunk visit frequency, and chest density.
 */
public class PlayerBaseDetector {

	/**
	 * Detects and updates the player's base location if confidence threshold is met.
	 * @return The detected base location, or null if confidence is too low.
	 */
	public static BlockPos detectPlayerBase(EntityPlayer player, PlayerCapability cap) {
		World world = player.world;
		long currentTime = world.getTotalWorldTime();

		// Get spawn/bed location as primary indicator
		BlockPos spawnOrBed = getSpawnOrBedLocation(player);

		// Get the most visited chunk
		ChunkPos mostVisitedChunk = getMostVisitedChunk(cap);

		if (mostVisitedChunk == null) {
			return null; // Not enough data yet
		}

		// Check if most visited chunk meets minimum visit requirements
		int visitCount = cap.getChunkVisitCount(mostVisitedChunk);
		if (visitCount < Settings.baseDetection.minChunkVisits) {
			return null; // Not enough visits yet
		}

		// Check if enough time has passed in this area
		long firstVisitTime = cap.getChunkFirstVisitTime(mostVisitedChunk);
		long timeInArea = (currentTime - firstVisitTime) / 20; // Convert to seconds
		if (timeInArea < Settings.baseDetection.minTimeInArea * 60) {
			return null; // Not enough time spent in area
		}

		// Calculate center position of most visited chunk
		BlockPos chunkCenter = new BlockPos(mostVisitedChunk.x * 16 + 8, 64, mostVisitedChunk.z * 16 + 8);
		chunkCenter = world.getHeight(chunkCenter); // Adjust to surface

		// Calculate confidence score
		float confidence = calculateBaseConfidence(player, chunkCenter, cap);

		if (confidence >= Settings.baseDetection.confidenceThreshold) {
			// Scan for chests in the area
			List<BlockPos> chestLocations = scanForChests(world, chunkCenter, Settings.baseDetection.scanRadius);

			// Update capability data
			cap.baseLocation = chunkCenter;
			cap.baseDimension = world.provider.getDimension();
			cap.chestCountAtBase = chestLocations.size();
			cap.chestValueScore = calculateChestValue(world, chestLocations);
			cap.lastBaseDetectionTime = currentTime;

			return chunkCenter;
		}

		return null;
	}

	/**
	 * Calculates a confidence score (0.0-1.0) for a candidate base location.
	 */
	public static float calculateBaseConfidence(EntityPlayer player, BlockPos candidate, PlayerCapability cap) {
		float confidence = 0.0F;

		// Check if spawn/bed is nearby (+40% confidence)
		BlockPos spawnOrBed = getSpawnOrBedLocation(player);
		if (spawnOrBed != null && spawnOrBed.distanceSq(candidate) < 10000) { // Within ~100 blocks
			confidence += 0.4F;
		}

		// Check visit frequency (+30% confidence based on visit count)
		ChunkPos candidateChunk = new ChunkPos(candidate);
		int visitCount = cap.getChunkVisitCount(candidateChunk);
		float visitScore = Math.min(visitCount / 50.0F, 1.0F); // Normalize to 50 visits
		confidence += visitScore * 0.3F;

		// Check for chest density (+20% confidence)
		List<BlockPos> chests = scanForChests(player.world, candidate, Settings.baseDetection.scanRadius);
		if (chests.size() >= Settings.baseDetection.minChestsForBase) {
			float chestScore = Math.min(chests.size() / 10.0F, 1.0F); // Normalize to 10 chests
			confidence += chestScore * 0.2F;
		}

		// Check time spent in area (+10% confidence)
		long firstVisit = cap.getChunkFirstVisitTime(candidateChunk);
		if (firstVisit > 0) {
			long currentTime = player.world.getTotalWorldTime();
			long timeInArea = (currentTime - firstVisit) / 20 / 60; // Convert to minutes
			float timeScore = Math.min(timeInArea / (float) Settings.baseDetection.minTimeInArea, 1.0F);
			confidence += timeScore * 0.1F;
		}

		return confidence;
	}

	/**
	 * Gets the player's spawn point or bed location.
	 */
	public static BlockPos getSpawnOrBedLocation(EntityPlayer player) {
		// Try bed location first
		BlockPos bedLocation = player.getBedLocation(player.dimension);
		if (bedLocation != null) {
			return bedLocation;
		}

		// Fall back to spawn point
		BlockPos spawnPoint = player.world.getSpawnPoint();
		return spawnPoint;
	}

	/**
	 * Returns the chunk with the most visits from the player.
	 */
	public static ChunkPos getMostVisitedChunk(PlayerCapability cap) {
		Map<Long, Integer> visits = cap.getChunkVisits();

		if (visits.isEmpty()) {
			return null;
		}

		// Find chunk with most visits
		long mostVisitedHash = -1;
		int maxVisits = 0;

		for (Map.Entry<Long, Integer> entry : visits.entrySet()) {
			if (entry.getValue() > maxVisits) {
				maxVisits = entry.getValue();
				mostVisitedHash = entry.getKey();
			}
		}

		if (mostVisitedHash == -1) {
			return null;
		}

		// Convert hash back to ChunkPos
		int x = (int) mostVisitedHash;
		int z = (int) (mostVisitedHash >> 32);
		return new ChunkPos(x, z);
	}

	/**
	 * Scans for chest blocks in a radius around the center position.
	 * @param radius Radius in chunks to scan.
	 */
	public static List<BlockPos> scanForChests(World world, BlockPos center, int radius) {
		List<BlockPos> chestLocations = new ArrayList<>();

		int chunkRadius = radius;
		ChunkPos centerChunk = new ChunkPos(center);

		for (int chunkX = centerChunk.x - chunkRadius; chunkX <= centerChunk.x + chunkRadius; chunkX++) {
			for (int chunkZ = centerChunk.z - chunkRadius; chunkZ <= centerChunk.z + chunkRadius; chunkZ++) {
				if (!world.isChunkGeneratedAt(chunkX, chunkZ)) {
					continue; // Skip ungenerated chunks
				}

				// Scan loaded chunks only
				if (world.isBlockLoaded(new BlockPos(chunkX * 16, 64, chunkZ * 16))) {
					scanChunkForChests(world, chunkX, chunkZ, chestLocations);
				}
			}
		}

		return chestLocations;
	}

	/**
	 * Scans a single chunk for chest tile entities.
	 */
	private static void scanChunkForChests(World world, int chunkX, int chunkZ, List<BlockPos> chestLocations) {
		// Iterate through all tile entities in the chunk
		for (TileEntity te : world.getChunk(chunkX, chunkZ).getTileEntityMap().values()) {
			if (te instanceof TileEntityChest || te instanceof IInventory) {
				// Check if it's actually a chest block (not just any inventory)
				if (world.getBlockState(te.getPos()).getBlock() == Blocks.CHEST || 
					world.getBlockState(te.getPos()).getBlock() == Blocks.TRAPPED_CHEST) {
					chestLocations.add(te.getPos());
				}
			}
		}
	}

	/**
	 * Calculates the total value score of items in the given chests.
	 */
	public static float calculateChestValue(World world, List<BlockPos> chestLocations) {
		float totalValue = 0.0F;

		for (BlockPos pos : chestLocations) {
			TileEntity te = world.getTileEntity(pos);
			if (te instanceof IInventory) {
				IInventory inventory = (IInventory) te;
				for (int i = 0; i < inventory.getSizeInventory(); i++) {
					ItemStack stack = inventory.getStackInSlot(i);
					if (!stack.isEmpty()) {
						totalValue += getItemValue(stack);
					}
				}
			}
		}

		return totalValue;
	}

	/**
	 * Gets the value score for an item stack based on configuration.
	 */
	public static float getItemValue(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0.0F;
		}

		float value = 0.0F;
		Item item = stack.getItem();

		// Check for specific valuable items
		if (item == Item.getItemFromBlock(Blocks.DIAMOND_BLOCK)) {
			value = Settings.itemValues.diamondBlock;
		} else if (item == Items.DIAMOND) {
			value = Settings.itemValues.diamond;
		} else if (item == Item.getItemFromBlock(Blocks.GOLD_BLOCK)) {
			value = Settings.itemValues.goldBlock;
		} else if (item == Items.GOLD_INGOT) {
			value = Settings.itemValues.gold;
		} else if (item == Item.getItemFromBlock(Blocks.IRON_BLOCK)) {
			value = Settings.itemValues.ironBlock;
		} else if (item == Items.IRON_INGOT) {
			value = Settings.itemValues.iron;
		} else if (item == Items.EMERALD) {
			value = Settings.itemValues.diamond * 0.8F; // Emeralds slightly less than diamonds
		} else if (item == Item.getItemFromBlock(Blocks.EMERALD_BLOCK)) {
			value = Settings.itemValues.diamondBlock * 0.8F;
		}
		// Add more item values as needed

		// Apply enchantment multiplier
		if (stack.isItemEnchanted()) {
			value *= Settings.itemValues.enchantedMultiplier;
		}

		// Multiply by stack size (with diminishing returns to prevent exploit)
		value *= Math.min(stack.getCount(), 16) / 16.0F;

		return value;
	}

	/**
	 * Main update method to be called periodically to refresh base detection.
	 */
	public static void updateBaseDetection(EntityPlayer player, PlayerCapability cap) {
		if (player.world.isRemote) {
			return; // Server-side only
		}

		BlockPos detectedBase = detectPlayerBase(player, cap);

		// Log if base location changed significantly
		if (detectedBase != null && cap.baseLocation != null) {
			if (detectedBase.distanceSq(cap.baseLocation) > 10000) { // Moved >100 blocks
				// Base has moved, reset robbery progress partially
				cap.robberyProgress *= 0.5F; // Halve progress when base moves
				cap.sync();
			}
		}
	}
}
