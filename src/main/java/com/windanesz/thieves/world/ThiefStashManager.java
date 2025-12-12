package com.windanesz.thieves.world;

import com.windanesz.thieves.Thieves;
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
	 * @return The position where the stash was created, or null if failed
	 */
	@Nullable
	public BlockPos createStash(World world, BlockPos nearLocation, ItemStackHandler lootContents, BlockPos associatedBase) {
		int dimensionId = world.provider.getDimension();

		// Check if there's already a stash near this base
		if (hasStashNearLocation(world, associatedBase, 500)) {
			// Remove old stash and replace with new one
			removeStashNearBase(dimensionId, associatedBase);
		}

		// Find suitable location for stash
		BlockPos stashPos = findStashLocation(world, nearLocation);

		if (stashPos == null) {
			Thieves.LOGGER.warn("Could not find suitable stash location near {}", nearLocation);
			return null;
		}

		// Generate stash structure
		generateStashStructure(world, stashPos);

		// Place chest with loot
		placeStashChest(world, stashPos, lootContents);

		// Record stash in data
		StashData stashData = new StashData(stashPos, world.getTotalWorldTime(), dimensionId, associatedBase);
		stashesByDimension.computeIfAbsent(dimensionId, k -> new ArrayList<>()).add(stashData);

		markDirty();

		Thieves.LOGGER.info("Created thief stash at {} in dimension {}", stashPos, dimensionId);

		return stashPos;
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
	 * Removes stashes near a base location.
	 */
	private void removeStashNearBase(int dimensionId, BlockPos baseLocation) {
		List<StashData> stashes = stashesByDimension.get(dimensionId);

		if (stashes != null) {
			stashes.removeIf(stash -> stash.associatedBase != null && stash.associatedBase.equals(baseLocation));
			markDirty();
		}
	}

	/**
	 * Finds a suitable underground location for a stash.
	 */
	@Nullable
	private BlockPos findStashLocation(World world, BlockPos nearLocation) {
		// Try to find a location 50-150 blocks away
		for (int attempt = 0; attempt < 100; attempt++) {
			int distance = 50 + RANDOM.nextInt(101); // 50-150 blocks
			double angle = RANDOM.nextDouble() * Math.PI * 2.0D;

			int offsetX = (int) (Math.cos(angle) * distance);
			int offsetZ = (int) (Math.sin(angle) * distance);

			BlockPos candidatePos = nearLocation.add(offsetX, 0, offsetZ);

			// Try to find a suitable Y level (underground, 30-50)
			for (int y = 30; y <= 50; y++) {
				BlockPos checkPos = new BlockPos(candidatePos.getX(), y, candidatePos.getZ());

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
		// Check if there's enough space (3x3x3)
		for (int x = -1; x <= 1; x++) {
			for (int y = 0; y <= 2; y++) {
				for (int z = -1; z <= 1; z++) {
					BlockPos checkPos = pos.add(x, y, z);
					IBlockState state = world.getBlockState(checkPos);

					// Must be replaceable (stone, dirt, etc.)
					if (!state.getBlock().isReplaceable(world, checkPos) && 
						state.getBlock() != Blocks.STONE &&
						state.getBlock() != Blocks.DIRT &&
						state.getBlock() != Blocks.COBBLESTONE) {
						return false;
					}
				}
			}
		}

		return true;
	}

	/**
	 * Generates a small stash structure.
	 */
	private void generateStashStructure(World world, BlockPos pos) {
		// Create a 3x3x3 room
		// Floor
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				world.setBlockState(pos.add(x, -1, z), Blocks.COBBLESTONE.getDefaultState());
			}
		}

		// Walls
		for (int x = -1; x <= 1; x++) {
			for (int y = 0; y <= 2; y++) {
				for (int z = -1; z <= 1; z++) {
					BlockPos blockPos = pos.add(x, y, z);

					// Walls
					if (x == -1 || x == 1 || z == -1 || z == 1 || y == 2) {
						// Add some variety
						if (RANDOM.nextFloat() < 0.2F) {
							world.setBlockState(blockPos, Blocks.MOSSY_COBBLESTONE.getDefaultState());
						} else if (RANDOM.nextFloat() < 0.1F) {
							world.setBlockState(blockPos, Blocks.STONEBRICK.getDefaultState());
						} else {
							world.setBlockState(blockPos, Blocks.COBBLESTONE.getDefaultState());
						}
					} else {
						// Interior - clear it
						world.setBlockToAir(blockPos);
					}
				}
			}
		}

		// Add a torch or two
		if (RANDOM.nextBoolean()) {
			world.setBlockState(pos.add(1, 1, 0), Blocks.TORCH.getDefaultState());
		}
		if (RANDOM.nextBoolean()) {
			world.setBlockState(pos.add(-1, 1, 0), Blocks.TORCH.getDefaultState());
		}
	}

	/**
	 * Places a chest with loot at the stash location.
	 */
	private void placeStashChest(World world, BlockPos pos, ItemStackHandler lootContents) {
		// Place chest in center
		world.setBlockState(pos, Blocks.CHEST.getDefaultState());

		// Get chest tile entity and fill with loot
		TileEntity te = world.getTileEntity(pos);
		if (te instanceof TileEntityChest) {
			TileEntityChest chest = (TileEntityChest) te;

			// Transfer items from loot bag to chest
			for (int i = 0; i < lootContents.getSlots() && i < chest.getSizeInventory(); i++) {
				ItemStack stack = lootContents.getStackInSlot(i);
				if (!stack.isEmpty()) {
					chest.setInventorySlotContents(i, stack.copy());
				}
			}

			chest.markDirty();
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		stashesByDimension.clear();

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
