package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.block.TileEntityLootBag;
import com.windanesz.thieves.entity.EntityMasterThief;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.init.ModBlocks;
import com.windanesz.thieves.util.PlayerBaseDetector;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * AI task for thieves to steal items from chests and deposit them in a loot bag.
 */
public class ThiefAIStealToLootBag extends EntityAIBase {

	private enum State {
		IDLE,
		SEEKING_CHEST,
		NAVIGATING_TO_CHEST,
		EXTRACTING_ITEMS,
		NAVIGATING_TO_BAG,
		DEPOSITING_ITEMS
	}

	private final EntityThief thief;
	private final World world;
	private final Random random = new Random();

	private State currentState = State.IDLE;
	private BlockPos targetChestPos;
	private BlockPos lootBagPos;
	private List<ItemStack> carriedItems = new ArrayList<>();
	private int extractionTimer = 0;
	private int searchCooldown = 0;
	private boolean chestOpened = false;

	private static final int CHEST_SEARCH_RADIUS = 32;
	private static final int BAG_SEARCH_RADIUS = 48;
	private static final int EXTRACTION_TIME = 40; // 2 seconds to extract items
	private static final int SEARCH_COOLDOWN_TIME = 60; // 3 seconds between searches

	public ThiefAIStealToLootBag(EntityThief thief) {
		this.thief = thief;
		this.world = thief.world;
		this.setMutexBits(3); // Movement mutex
	}

	@Override
	public boolean shouldExecute() {
		// Don't execute if thief already has a loot bag in inventory
		if (thiefHasLootBag()) {
			return false;
		}
		
		// Don't execute if on cooldown
		if (searchCooldown > 0) {
			searchCooldown--;
			return false;
		}

		// Find loot bag first
		if (lootBagPos == null || !isValidLootBag(lootBagPos)) {
			lootBagPos = findNearestLootBag();
			if (lootBagPos == null) {
				searchCooldown = SEARCH_COOLDOWN_TIME * 4; // Longer cooldown if no bag found
				return false;
			}
		}

		// Check if loot bag has reached thief limit
		if (hasReachedThiefLimit(lootBagPos)) {
			return false; // Bag has reached limit, can't steal more
		}

		// Find a chest with items
		targetChestPos = findNearestAccessibleChest();
		if (targetChestPos == null) {
			searchCooldown = SEARCH_COOLDOWN_TIME;
			return false;
		}

		return true;
	}

	@Override
	public boolean shouldContinueExecuting() {
		// Continue until we complete the deposit or something goes wrong
		return currentState != State.IDLE && 
			   lootBagPos != null && 
			   isValidLootBag(lootBagPos) &&
			   !hasReachedThiefLimit(lootBagPos);
	}

	@Override
	public void startExecuting() {
		currentState = State.NAVIGATING_TO_CHEST;
		extractionTimer = 0;
	}

	@Override
	public void resetTask() {
		closeChest();
		clearHeldItem();
		currentState = State.IDLE;
		targetChestPos = null;
		carriedItems.clear();
		extractionTimer = 0;
		thief.getNavigator().clearPath();
	}

	@Override
	public void updateTask() {
		switch (currentState) {
			case NAVIGATING_TO_CHEST:
				updateNavigatingToChest();
				break;
			case EXTRACTING_ITEMS:
				updateExtractingItems();
				break;
			case NAVIGATING_TO_BAG:
				updateNavigatingToBag();
				break;
			case DEPOSITING_ITEMS:
				updateDepositingItems();
				break;
		}
	}

	private void updateNavigatingToChest() {
		if (targetChestPos == null) {
			currentState = State.IDLE;
			return;
		}

		// Look at chest
		thief.getLookHelper().setLookPosition(
			targetChestPos.getX() + 0.5,
			targetChestPos.getY() + 0.5,
			targetChestPos.getZ() + 0.5,
			10.0F,
			thief.getVerticalFaceSpeed()
		);

		// Get position in front of chest
		Vec3d targetPos = getPositionInFrontOfChest(targetChestPos);

		// Check if close enough to the target position
		double distSq = thief.getDistanceSq(targetPos.x, targetPos.y, targetPos.z);
		if (distSq < 2.25D) { // 1.5 blocks
			// Reached position in front of chest, start extracting
			currentState = State.EXTRACTING_ITEMS;
			extractionTimer = EXTRACTION_TIME;
			thief.getNavigator().clearPath();
		} else {
			// Navigate to position in front of chest
			double speed = getStealingSpeed();
			thief.getNavigator().tryMoveToXYZ(
				targetPos.x,
				targetPos.y,
				targetPos.z,
				speed
			);
		}
	}

	private void updateExtractingItems() {
		// Open chest on first tick
		if (!chestOpened) {
			openChest();
		}

		extractionTimer--;

		if (extractionTimer <= 0) {
			// Extract items from chest
			boolean success = extractItemsFromChest();
			
			// Close chest after extraction
			closeChest();
			
			if (success && !carriedItems.isEmpty()) {
				// Hold the first stolen item in offhand
				thief.setHeldItem(EnumHand.OFF_HAND, carriedItems.get(0).copy());
				// Successfully extracted, now navigate to bag
				currentState = State.NAVIGATING_TO_BAG;
			} else {
				// Failed or chest is empty, reset
				currentState = State.IDLE;
			}
		}
	}

	private void updateNavigatingToBag() {
		if (lootBagPos == null || carriedItems.isEmpty()) {
			currentState = State.IDLE;
			return;
		}

		// Look at bag
		thief.getLookHelper().setLookPosition(
			lootBagPos.getX() + 0.5,
			lootBagPos.getY() + 0.5,
			lootBagPos.getZ() + 0.5,
			10.0F,
			thief.getVerticalFaceSpeed()
		);

		// Check if close enough
		if (thief.getDistanceSq(lootBagPos) < 4.0D) {
			// Reached bag, start depositing
			currentState = State.DEPOSITING_ITEMS;
			thief.getNavigator().clearPath();
		} else {
			// Navigate to bag
			double speed = getStealingSpeed();
			thief.getNavigator().tryMoveToXYZ(
				lootBagPos.getX() + 0.5,
				lootBagPos.getY(),
				lootBagPos.getZ() + 0.5,
				speed
			);
		}
	}

	private void updateDepositingItems() {
		// Deposit items into loot bag
		depositItemsInBag();
		
		// Clear the held item
		clearHeldItem();
		
		// Clear carried items
		carriedItems.clear();
		
		// Return to idle (will search for next chest)
		currentState = State.IDLE;
	}

	private BlockPos findNearestLootBag() {
		BlockPos thiefPos = thief.getPosition();

		for (int x = -BAG_SEARCH_RADIUS; x <= BAG_SEARCH_RADIUS; x++) {
			for (int z = -BAG_SEARCH_RADIUS; z <= BAG_SEARCH_RADIUS; z++) {
				for (int y = -8; y <= 8; y++) {
					BlockPos checkPos = thiefPos.add(x, y, z);
					
					if (world.getBlockState(checkPos).getBlock() == ModBlocks.LOOT_BAG) {
						return checkPos;
					}
				}
			}
		}

		return null;
	}

	private BlockPos findNearestAccessibleChest() {
		List<BlockPos> chests = scanForChests();

		if (chests.isEmpty()) {
			return null;
		}

		// Master thieves prioritize valuable items
		if (thief instanceof EntityMasterThief) {
			return findChestWithValuableItems(chests);
		}

		// Regular thieves pick random chest
		return chests.get(random.nextInt(chests.size()));
	}

	private List<BlockPos> scanForChests() {
		List<BlockPos> chests = new ArrayList<>();
		BlockPos thiefPos = thief.getPosition();

		for (int x = -CHEST_SEARCH_RADIUS; x <= CHEST_SEARCH_RADIUS; x++) {
			for (int z = -CHEST_SEARCH_RADIUS; z <= CHEST_SEARCH_RADIUS; z++) {
				for (int y = -8; y <= 8; y++) {
					BlockPos checkPos = thiefPos.add(x, y, z);
					
					if (world.getBlockState(checkPos).getBlock() == Blocks.CHEST ||
						world.getBlockState(checkPos).getBlock() == Blocks.TRAPPED_CHEST) {
						
						// Check if chest is accessible (has items)
						TileEntity te = world.getTileEntity(checkPos);
						if (te instanceof IInventory && hasAccessibleItems((IInventory) te)) {
							chests.add(checkPos);
						}
					}
				}
			}
		}

		return chests;
	}

	private BlockPos findChestWithValuableItems(List<BlockPos> chests) {
		BlockPos bestChest = null;
		float bestValue = 0.0F;

		for (BlockPos chestPos : chests) {
			TileEntity te = world.getTileEntity(chestPos);
			if (te instanceof IInventory) {
				float value = calculateChestValue((IInventory) te);
				if (value > bestValue) {
					bestValue = value;
					bestChest = chestPos;
				}
			}
		}

		return bestChest != null ? bestChest : (chests.isEmpty() ? null : chests.get(0));
	}

	private float calculateChestValue(IInventory inventory) {
		float totalValue = 0.0F;
		for (int i = 0; i < inventory.getSizeInventory(); i++) {
			ItemStack stack = inventory.getStackInSlot(i);
			if (!stack.isEmpty()) {
				totalValue += PlayerBaseDetector.getItemValue(stack);
			}
		}
		return totalValue;
	}

	private boolean hasAccessibleItems(IInventory inventory) {
		for (int i = 0; i < inventory.getSizeInventory(); i++) {
			if (!inventory.getStackInSlot(i).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private boolean extractItemsFromChest() {
		TileEntity te = world.getTileEntity(targetChestPos);
		if (!(te instanceof IInventory)) {
			return false;
		}

		IInventory inventory = (IInventory) te;
		
		// Find a random occupied slot
		List<Integer> occupiedSlots = new ArrayList<>();
		for (int i = 0; i < inventory.getSizeInventory(); i++) {
			if (!inventory.getStackInSlot(i).isEmpty()) {
				occupiedSlots.add(i);
			}
		}

		if (occupiedSlots.isEmpty()) {
			return false;
		}

		// Pick random slot
		int slot = occupiedSlots.get(random.nextInt(occupiedSlots.size()));
		ItemStack stack = inventory.getStackInSlot(slot);

		// Extract 1-8 items (or full stack if smaller)
		int maxExtract = thief instanceof EntityMasterThief ? 16 : 8;
		int extractCount = Math.min(stack.getCount(), 1 + random.nextInt(maxExtract));
		
		ItemStack extracted = stack.splitStack(extractCount);
		carriedItems.add(extracted);

		inventory.markDirty();

		return true;
	}

	private void depositItemsInBag() {
		TileEntity te = world.getTileEntity(lootBagPos);
		if (!(te instanceof TileEntityLootBag)) {
			return;
		}

		TileEntityLootBag lootBag = (TileEntityLootBag) te;

		for (ItemStack stack : carriedItems) {
			lootBag.addItem(stack);
		}

		lootBag.markDirty();
	}

	private boolean isValidLootBag(BlockPos pos) {
		return world.getBlockState(pos).getBlock() == ModBlocks.LOOT_BAG;
	}

	private boolean hasReachedThiefLimit(BlockPos pos) {
		TileEntity te = world.getTileEntity(pos);
		if (te instanceof TileEntityLootBag) {
			return ((TileEntityLootBag) te).hasReachedThiefLimit();
		}
		return false;
	}

	private double getStealingSpeed() {
		double baseSpeed = 1.0D;
		if (thief instanceof EntityMasterThief) {
			baseSpeed *= ((EntityMasterThief) thief).getStealingSpeedMultiplier();
		}
		return baseSpeed;
	}

	private Vec3d getPositionInFrontOfChest(BlockPos chestPos) {
		// Get the chest's facing direction
		EnumFacing facing = world.getBlockState(chestPos).getValue(net.minecraft.block.BlockChest.FACING);
		
		// Calculate position 1.5 blocks in front of the chest
		BlockPos frontPos = chestPos.offset(facing, 1);
		
		return new Vec3d(frontPos.getX() + 0.5, frontPos.getY(), frontPos.getZ() + 0.5);
	}

	private void clearHeldItem() {
		thief.setHeldItem(EnumHand.OFF_HAND, ItemStack.EMPTY);
	}

	private void openChest() {
		if (targetChestPos != null && !chestOpened) {
			TileEntity te = world.getTileEntity(targetChestPos);
			if (te instanceof TileEntityChest) {
				TileEntityChest chest = (TileEntityChest) te;
				// Increment the numPlayersUsing counter to open the chest visually
				world.addBlockEvent(targetChestPos, chest.getBlockType(), 1, 1);
				world.notifyNeighborsOfStateChange(targetChestPos, chest.getBlockType(), false);
				chestOpened = true;
			}
		}
	}

	private void closeChest() {
		if (targetChestPos != null && chestOpened) {
			TileEntity te = world.getTileEntity(targetChestPos);
			if (te instanceof TileEntityChest) {
				TileEntityChest chest = (TileEntityChest) te;
				// Decrement the numPlayersUsing counter to close the chest visually
				world.addBlockEvent(targetChestPos, chest.getBlockType(), 1, 0);
				world.notifyNeighborsOfStateChange(targetChestPos, chest.getBlockType(), false);
				chestOpened = false;
			}
		}
	}

	private boolean thiefHasLootBag() {
		ItemStack offhand = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		return !offhand.isEmpty() && offhand.getItem() instanceof com.windanesz.thieves.item.ItemLootBag;
	}
}
