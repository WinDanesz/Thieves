package com.windanesz.thieves.world;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.block.TileEntityLootBag;
import com.windanesz.thieves.init.ModBlocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Manages persistent thief stash locations across the world.
 * Stores stash data per dimension.
 */
public class ThiefStashManager extends WorldSavedData {

	private static final String DATA_NAME = "thieves_stash_manager";
	private static final Random RANDOM = new Random();

	// Dimension ID -> List of stashes
	private Map<Integer, List<StashData>> stashesByDimension = new HashMap<>();
	
	// Dimension ID -> List of hideout positions
	private Map<Integer, List<BlockPos>> hideoutsByDimension = new HashMap<>();

	public ThiefStashManager() {
		super(DATA_NAME);
	}

	public ThiefStashManager(String name) {
		super(name);
	}

	/**
	 * Gets the stash manager for a world.
	 */
	public static ThiefStashManager get(World world) {
		MapStorage storage = world.getMapStorage();
		ThiefStashManager instance = (ThiefStashManager) storage.getOrLoadData(ThiefStashManager.class, DATA_NAME);

		if (instance == null) {
			instance = new ThiefStashManager();
			storage.setData(DATA_NAME, instance);
		}

		return instance;
	}

	/**
	 * Creates a stash at a location and stores the loot.
	 * @param nearLocation Position near where to create the stash
	 * @param lootContents The stolen items to store
	 * @param associatedBase The player's base this stash is associated with
	 * @return The position of the loot bag, or null if failed
	 */
	@Nullable
	public BlockPos createStash(World world, BlockPos nearLocation, ItemStackHandler lootContents, BlockPos associatedBase) {
		int dimensionId = world.provider.getDimension();

		// Check if there's already a stash near this base
		if (hasStashNearLocation(world, associatedBase, 500)) {
			// Remove old stash and replace with new one
			removeStashNearBase(world, dimensionId, associatedBase);
		}

		// Find suitable location for stash
		BlockPos stashPos = findStashLocation(world, nearLocation);

		if (stashPos == null) {
			Thieves.LOGGER.warn("Could not find suitable stash location near {}", nearLocation);
			return null;
		}

		// Place loot bag with loot and get its actual position
		BlockPos lootBagPos = placeStashChest(world, stashPos, lootContents);

		// Record stash in data
		StashData stashData = new StashData(stashPos, world.getTotalWorldTime(), dimensionId, associatedBase);
		stashesByDimension.computeIfAbsent(dimensionId, k -> new ArrayList<>()).add(stashData);

		markDirty();

		Thieves.LOGGER.info("Created thief stash at {} in dimension {}", stashPos, dimensionId);

		return lootBagPos;
	}

	/**
	 * Checks if there's a stash near a location.
	 */
	public boolean hasStashNearLocation(World world, BlockPos location, int radius) {
		int dimensionId = world.provider.getDimension();
		List<StashData> stashes = stashesByDimension.get(dimensionId);

		if (stashes == null) {
			return false;
		}

		for (StashData stash : stashes) {
			if (stash.position.distanceSq(location) < radius * radius) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Gets all stashes in a dimension.
	 */
	public List<StashData> getStashesInDimension(int dimensionId) {
		return new ArrayList<>(stashesByDimension.getOrDefault(dimensionId, new ArrayList<>()));
	}

	/**
	 * Removes a stash at a specific location.
	 */
	public void removeStash(World world, BlockPos location) {
		int dimensionId = world.provider.getDimension();
		List<StashData> stashes = stashesByDimension.get(dimensionId);

		if (stashes != null) {
			stashes.removeIf(stash -> stash.position.equals(location));
			markDirty();
		}
	}
	
	/**
	 * Registers a hideout location in the world data.
	 */
	public void registerHideout(int dimensionId, BlockPos position) {
		List<BlockPos> hideouts = hideoutsByDimension.computeIfAbsent(dimensionId, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
		
		// Don't register if already present
		if (!hideouts.contains(position)) {
			hideouts.add(position);
			markDirty();
			Thieves.LOGGER.info("Registered hideout at {} in dimension {}", position, dimensionId);
		}
	}
	
	/**
	 * Removes a hideout from the registry.
	 */
	public void removeHideout(int dimensionId, BlockPos position) {
		List<BlockPos> hideouts = hideoutsByDimension.get(dimensionId);
		
		if (hideouts != null) {
			hideouts.remove(position);
			markDirty();
			Thieves.LOGGER.info("Removed hideout at {} from dimension {}", position, dimensionId);
		}
	}
	
	/**
	 * Finds the nearest hideout to a position within a maximum distance.
	 * Validates that the hideout still exists (has a loot bag).
	 * @param world The world instance
	 * @param position The position to search from
	 * @param maxDistance Maximum distance in blocks
	 * @return The nearest valid hideout position, or null if none found
	 */
	@Nullable
	public BlockPos getNearestHideout(World world, BlockPos position, double maxDistance) {
		int dimensionId = world.provider.getDimension();
		List<BlockPos> hideouts = hideoutsByDimension.get(dimensionId);
		
		if (hideouts == null || hideouts.isEmpty()) {
			Thieves.LOGGER.debug("No hideouts registered in dimension {}", dimensionId);
			return null;
		}
		
		BlockPos nearest = null;
		double nearestDistSq = maxDistance * maxDistance;
		
		for (BlockPos hideoutPos : hideouts) {
			double distSq = position.distanceSq(hideoutPos);
			
			// Skip if beyond max distance
			if (distSq >= nearestDistSq) {
				continue;
			}
			
			// Only validate if chunk is loaded (don't reject unloaded chunks)
			if (world.isBlockLoaded(hideoutPos)) {
				if (!isHideoutValidLoaded(world, hideoutPos)) {
					Thieves.LOGGER.warn("Hideout at {} is loaded but invalid, will clean up later", hideoutPos);
					continue;
				}
			}
			
			// This hideout is closest so far
			nearestDistSq = distSq;
			nearest = hideoutPos;
		}
		
		if (nearest != null) {
			Thieves.LOGGER.info("Found nearest hideout at {} (distance: {} blocks)", nearest, Math.sqrt(nearestDistSq));
		} else {
			Thieves.LOGGER.info("No valid hideouts found within {} blocks of {}", maxDistance, position);
		}
		
		return nearest;
	}
	
	/**
	 * Checks if a loaded hideout is still valid.
	 */
	private boolean isHideoutValidLoaded(World world, BlockPos position) {
		if (world.getBlockState(position).getBlock() != ModBlocks.LOOT_BAG) {
			return false;
		}
		
		TileEntity te = world.getTileEntity(position);
		if (te instanceof TileEntityLootBag) {
			return ((TileEntityLootBag) te).isHideout();
		}
		
		return false;
	}
	
	/**
	 * Cleans up invalid hideouts in a dimension (call periodically or when needed).
	 */
	public void cleanupInvalidHideouts(World world, int dimensionId) {
		List<BlockPos> hideouts = hideoutsByDimension.get(dimensionId);
		if (hideouts == null || hideouts.isEmpty()) {
			return;
		}
		
		List<BlockPos> invalidHideouts = new ArrayList<>();
		
		for (BlockPos hideoutPos : hideouts) {
			// Only check loaded chunks
			if (world.isBlockLoaded(hideoutPos)) {
				if (!isHideoutValidLoaded(world, hideoutPos)) {
					invalidHideouts.add(hideoutPos);
				}
			}
		}
		
		if (!invalidHideouts.isEmpty()) {
			hideouts.removeAll(invalidHideouts);
			markDirty();
			Thieves.LOGGER.info("Cleaned up {} invalid hideouts in dimension {}", invalidHideouts.size(), dimensionId);
		}
	}
	
	/**
	 * Checks if there's an active hideout at a specific position.
	 */
	public boolean isHideoutActive(int dimensionId, BlockPos position) {
		List<BlockPos> hideouts = hideoutsByDimension.get(dimensionId);
		return hideouts != null && hideouts.contains(position);
	}

	/**
	 * Removes stashes near a base location and destroys the physical block if it's a loot bag.
	 */
	private void removeStashNearBase(World world, int dimensionId, BlockPos baseLocation) {
		List<StashData> stashes = stashesByDimension.get(dimensionId);

		if (stashes != null) {
			stashes.removeIf(stash -> {
				if (stash.associatedBase != null && stash.associatedBase.equals(baseLocation)) {
					// Break the old stash block if it's loaded and still a loot bag
					if (world.isBlockLoaded(stash.position)) {
						if (world.getBlockState(stash.position).getBlock() == ModBlocks.LOOT_BAG) {
							world.setBlockToAir(stash.position);
						}
					}
					return true;
				}
				return false;
			});
			markDirty();
		}
	}

	/**
	 * Finds a suitable surface location for a stash.
	 */
	@Nullable
	private BlockPos findStashLocation(World world, BlockPos nearLocation) {
		// Try to find a location within 10 blocks of the escape position
		for (int attempt = 0; attempt < 100; attempt++) {
			int distance = 3 + RANDOM.nextInt(4); // 3 to 6 blocks
			double angle = RANDOM.nextDouble() * Math.PI * 2.0D;

			int offsetX = (int) (Math.cos(angle) * distance);
			int offsetZ = (int) (Math.sin(angle) * distance);

			BlockPos candidatePos = nearLocation.add(offsetX, 0, offsetZ);

			// Check vertical range around thief's position (floor level)
			for (int dy = -2; dy <= 2; dy++) {
				BlockPos checkPos = candidatePos.up(dy);
				if (isValidStashLocation(world, checkPos)) {
					return checkPos;
				}
			}
		}

		return null;
	}

	/**
	 * Checks if a location is valid for a stash.
	 */
	private boolean isValidStashLocation(World world, BlockPos pos) {
		// Check if position is on solid ground
		BlockPos below = pos.down();
		if (!world.getBlockState(below).isSideSolid(world, below, net.minecraft.util.EnumFacing.UP)) {
			return false;
		}

		// Check if there's enough air space above
		if (!world.isAirBlock(pos) || !world.isAirBlock(pos.up())) {
			return false;
		}

		return true;
	}

	/**
	 * Places the loot bag block at the stash location on the surface.
	 * @return The position where the loot bag was placed
	 */
	private BlockPos placeStashChest(World world, BlockPos pos, ItemStackHandler lootContents) {
		// Place loot bag block
		world.setBlockState(pos, ModBlocks.LOOT_BAG.getDefaultState());
		
		// Fill it with the stolen loot
		TileEntity te = world.getTileEntity(pos);
		if (te instanceof TileEntityLootBag) {
			TileEntityLootBag lootBag = (TileEntityLootBag) te;
			for (int i = 0; i < lootContents.getSlots(); i++) {
				ItemStack stack = lootContents.getStackInSlot(i);
				if (!stack.isEmpty()) {
					lootBag.addItem(stack);
				}
			}
			lootBag.markDirty();
		}
		
		return pos;
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		stashesByDimension.clear();
		hideoutsByDimension.clear();

		NBTTagList dimensionsList = nbt.getTagList("dimensions", 10); // 10 = compound

		for (int i = 0; i < dimensionsList.tagCount(); i++) {
			NBTTagCompound dimData = dimensionsList.getCompoundTagAt(i);
			int dimensionId = dimData.getInteger("dimensionId");

			List<StashData> stashes = new ArrayList<>();
			NBTTagList stashesList = dimData.getTagList("stashes", 10);

			for (int j = 0; j < stashesList.tagCount(); j++) {
				NBTTagCompound stashNBT = stashesList.getCompoundTagAt(j);
				StashData stash = StashData.fromNBT(stashNBT);
				if (stash != null) {
					stashes.add(stash);
				}
			}

			stashesByDimension.put(dimensionId, stashes);
		}
		
		// Read hideouts
		NBTTagList hideoutDimensionsList = nbt.getTagList("hideoutDimensions", 10);
		for (int i = 0; i < hideoutDimensionsList.tagCount(); i++) {
			NBTTagCompound dimData = hideoutDimensionsList.getCompoundTagAt(i);
			int dimensionId = dimData.getInteger("dimensionId");
			
			List<BlockPos> hideouts = new java.util.concurrent.CopyOnWriteArrayList<>();
			NBTTagList hideoutsList = dimData.getTagList("hideouts", 10);
			
			for (int j = 0; j < hideoutsList.tagCount(); j++) {
				NBTTagCompound hideoutNBT = hideoutsList.getCompoundTagAt(j);
				BlockPos pos = BlockPos.fromLong(hideoutNBT.getLong("pos"));
				hideouts.add(pos);
			}
			
			hideoutsByDimension.put(dimensionId, hideouts);
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound compound) {
		NBTTagList dimensionsList = new NBTTagList();

		for (Map.Entry<Integer, List<StashData>> entry : stashesByDimension.entrySet()) {
			NBTTagCompound dimData = new NBTTagCompound();
			dimData.setInteger("dimensionId", entry.getKey());

			NBTTagList stashesList = new NBTTagList();
			for (StashData stash : entry.getValue()) {
				stashesList.appendTag(stash.toNBT());
			}

			dimData.setTag("stashes", stashesList);
			dimensionsList.appendTag(dimData);
		}

		compound.setTag("dimensions", dimensionsList);
		
		// Write hideouts
		NBTTagList hideoutDimensionsList = new NBTTagList();
		for (Map.Entry<Integer, List<BlockPos>> entry : hideoutsByDimension.entrySet()) {
			NBTTagCompound dimData = new NBTTagCompound();
			dimData.setInteger("dimensionId", entry.getKey());
			
			NBTTagList hideoutsList = new NBTTagList();
			for (BlockPos pos : entry.getValue()) {
				NBTTagCompound hideoutNBT = new NBTTagCompound();
				hideoutNBT.setLong("pos", pos.toLong());
				hideoutsList.appendTag(hideoutNBT);
			}
			
			dimData.setTag("hideouts", hideoutsList);
			hideoutDimensionsList.appendTag(dimData);
		}
		
		compound.setTag("hideoutDimensions", hideoutDimensionsList);

		return compound;
	}

	/**
	 * Data class for a single stash.
	 */
	public static class StashData {
		public BlockPos position;
		public long createdTime;
		public int dimensionId;
		public BlockPos associatedBase;

		public StashData(BlockPos position, long createdTime, int dimensionId, BlockPos associatedBase) {
			this.position = position;
			this.createdTime = createdTime;
			this.dimensionId = dimensionId;
			this.associatedBase = associatedBase;
		}

		public NBTTagCompound toNBT() {
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setLong("position", position.toLong());
			nbt.setLong("createdTime", createdTime);
			nbt.setInteger("dimensionId", dimensionId);
			if (associatedBase != null) {
				nbt.setLong("associatedBase", associatedBase.toLong());
			}
			return nbt;
		}

		@Nullable
		public static StashData fromNBT(NBTTagCompound nbt) {
			if (!nbt.hasKey("position")) {
				return null;
			}

			BlockPos position = BlockPos.fromLong(nbt.getLong("position"));
			long createdTime = nbt.getLong("createdTime");
			int dimensionId = nbt.getInteger("dimensionId");
			BlockPos associatedBase = nbt.hasKey("associatedBase") ? BlockPos.fromLong(nbt.getLong("associatedBase")) : null;

			return new StashData(position, createdTime, dimensionId, associatedBase);
		}
	}
}
