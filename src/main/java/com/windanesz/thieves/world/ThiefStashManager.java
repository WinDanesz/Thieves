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
	 * Finds a suitable surface location for a half-dug hideout.
	 */
	@Nullable
	private BlockPos findStashLocation(World world, BlockPos nearLocation) {
		// Try to find a location within 20 blocks of the escape position
		for (int attempt = 0; attempt < 100; attempt++) {
			int distance = RANDOM.nextInt(21); 
			double angle = RANDOM.nextDouble() * Math.PI * 2.0D;

			int offsetX = (int) (Math.cos(angle) * distance);
			int offsetZ = (int) (Math.sin(angle) * distance);

			BlockPos candidatePos = nearLocation.add(offsetX, 0, offsetZ);

			// Find ground surface level
			BlockPos surfacePos = world.getHeight(candidatePos);

			if (isValidStashLocation(world, surfacePos)) {
				return surfacePos;
			}
		}

		return null;
	}

	/**
	 * Checks if a location is valid for a half-dug hideout.
	 */
	private boolean isValidStashLocation(World world, BlockPos pos) {
		// Check if position is on solid ground
		BlockPos below = pos.down();
		if (!world.getBlockState(below).isSideSolid(world, below, net.minecraft.util.EnumFacing.UP)) {
			return false;
		}

		// Check if there's enough air space above (need 2 blocks high for the hideout)
		if (!world.isAirBlock(pos) || !world.isAirBlock(pos.up())) {
			return false;
		}

		// Check 3x3 area is mostly clear
		int airBlocks = 0;
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				BlockPos checkPos = pos.add(x, 0, z);
				if (world.isAirBlock(checkPos) || world.getBlockState(checkPos).getBlock().isReplaceable(world, checkPos)) {
					airBlocks++;
				}
			}
		}

		// At least 6 out of 9 blocks should be air/replaceable
		return airBlocks >= 6;
	}

	/**
	 * Generates a simple half-dug hideout camouflaged with leaves or sandstone.
	 */
	private void generateStashStructure(World world, BlockPos pos) {
		// Determine if we should use sandstone (desert biome and over sand)
		boolean useSandstone = false;
		net.minecraft.world.biome.Biome biome = world.getBiome(pos);
		
		// Check if biome is hot (temperature > 1.0 typically means desert-like)
		if (biome.getTemperature(pos) > 1.0F) {
			// Check if ground blocks are sand
			boolean overSand = true;
			for (int x = -1; x <= 1 && overSand; x++) {
				for (int z = -1; z <= 1 && overSand; z++) {
					BlockPos checkPos = pos.add(x, -1, z);
					IBlockState groundBlock = world.getBlockState(checkPos);
					if (groundBlock.getBlock() != Blocks.SAND) {
						overSand = false;
					}
				}
			}
			useSandstone = overSand;
		}
		
		// Get camouflage block state
		IBlockState camouflageBlock;
		if (useSandstone) {
			camouflageBlock = Blocks.SANDSTONE.getDefaultState();
		} else {
			// Get persistent leaves state (won't decay)
			camouflageBlock = Blocks.LEAVES.getDefaultState()
					.withProperty(net.minecraft.block.BlockLeaves.CHECK_DECAY, false)
					.withProperty(net.minecraft.block.BlockLeaves.DECAYABLE, false);
		}
		
		// Excavate a 3x1x3 hole (2 blocks deep: y=0 and y=-1)
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				// Clear upper layer (y=0)
				BlockPos upperPos = pos.add(x, 0, z);
				world.setBlockToAir(upperPos);
				
				// Clear lower layer (y=-1) - this is where loot will be placed
				BlockPos lowerPos = pos.add(x, -1, z);
				world.setBlockToAir(lowerPos);
				
				// Ensure ground below is solid (y=-2)
				BlockPos below = pos.add(x, -2, z);
				if (world.isAirBlock(below) || !world.getBlockState(below).isOpaqueCube()) {
					world.setBlockState(below, Blocks.DIRT.getDefaultState());
				}
			}
		}
		
		// Surround with camouflage blocks on the sides (at ground level, y=0)
		for (int side = -1; side <= 1; side++) {
			// North and South walls
			world.setBlockState(pos.add(side, 0, -2), camouflageBlock);
			world.setBlockState(pos.add(side, 0, 2), camouflageBlock);
			// East and West walls (skip corners)
			if (side != -1 && side != 1) {
				world.setBlockState(pos.add(-2, 0, side), camouflageBlock);
				world.setBlockState(pos.add(2, 0, side), camouflageBlock);
			}
		}
		
		// Cover top with camouflage blocks (3x3 roof at y+1)
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				// Add some randomness - don't fully cover
				if (x == 0 && z == 0 && RANDOM.nextFloat() < 0.3F) {
					continue; // Small opening in center sometimes
				}
				world.setBlockState(pos.add(x, 1, z), camouflageBlock);
			}
		}
		
		// Maybe add an empty chest in a random corner (at lower level, y=-1)
		if (RANDOM.nextFloat() < 0.4F) { // 40% chance
			int cornerX = RANDOM.nextBoolean() ? -1 : 1;
			int cornerZ = RANDOM.nextBoolean() ? -1 : 1;
			BlockPos chestPos = pos.add(cornerX, -1, cornerZ);
			world.setBlockState(chestPos, Blocks.CHEST.getDefaultState());
		}
	}

	/**
	 * Places the loot bag block at the stash location.
	 */
	private void placeStashChest(World world, BlockPos pos, ItemStackHandler lootContents) {
		// Find a suitable position in the 3x3 area at the lower level (y=-1)
		List<BlockPos> availablePositions = new ArrayList<>();
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				BlockPos checkPos = pos.add(x, -1, z);
				if (world.isAirBlock(checkPos)) {
					availablePositions.add(checkPos);
				}
			}
		}
		
		if (availablePositions.isEmpty()) {
			// Fallback to center if nothing available
			availablePositions.add(pos);
		}
		
		// Pick random position for loot bag
		BlockPos bagPos = availablePositions.get(RANDOM.nextInt(availablePositions.size()));
		
		// Place loot bag block
		world.setBlockState(bagPos, ModBlocks.LOOT_BAG.getDefaultState());
		
		// Fill it with the stolen loot
		TileEntity te = world.getTileEntity(bagPos);
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
