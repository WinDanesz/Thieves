package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.block.TileEntityLootBag;
import com.windanesz.thieves.entity.EntityMasterThief;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.init.ModBlocks;
import com.windanesz.thieves.util.PlayerBaseDetector;
import com.windanesz.thieves.world.ThiefStashManager;
import net.minecraft.entity.ai.EntityAIBase;
import com.windanesz.thieves.item.ItemLootBag;
import net.minecraft.util.math.AxisAlignedBB;
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
		// Don't execute if thief already has a loot bag in inventory (should be escaping)
		if (thiefHasLootBag()) {
			return false;
		}

		// Don't execute if there is a thief with a loot bag nearby (should be escorting)
		if (isCarrierNearby()) {
			return false;
		}
		
		// Don't execute if on cooldown
		if (searchCooldown > 0) {
			searchCooldown--;
			return false;
		}

		boolean hasStolenItem = !thief.getHeldItemOffhand().isEmpty();

		// Find loot bag first
		if (lootBagPos == null || !isValidLootBag(lootBagPos)) {
			lootBagPos = null; // Reset invalid pos

			// Try robbery loot bag pos first
			if (thief.isRobberyThief() && thief.getRobberyLootBagPos() != null) {
				BlockPos robPos = thief.getRobberyLootBagPos();
				if (isValidLootBag(robPos)) {
					lootBagPos = robPos;
				}
			}
			
			// If still null, search for nearest
			if (lootBagPos == null) {
				lootBagPos = findNearestLootBag();
			}

			// If still null and we have an item, try to find a hideout
			if (lootBagPos == null && hasStolenItem) {
				lootBagPos = ThiefStashManager.get(world).getNearestHideout(world, thief.getPosition(), 500);
			}
			
			if (lootBagPos == null) {
				searchCooldown = (SEARCH_COOLDOWN_TIME * 4) + random.nextInt(60); // Longer cooldown if no bag found
				
				// If we can't find a loot bag, assume the raid is botched and try to escape/return to hideout
				// by forcing the thief to become a "robbery thief" which triggers the Escort task
				if (!thief.isRobberyThief()) {
					thief.setRobberyThief(true);
				}
				
				return false;
			}
		}

		// Check if loot bag has reached thief limit
		if (hasReachedThiefLimit(lootBagPos)) {
			lootBagPos = null; // Reset to find a new bag next time
			searchCooldown = SEARCH_COOLDOWN_TIME + random.nextInt(20);
			return false;
		}

		// If we already have a stolen item, skip chest search and go deposit
		if (hasStolenItem) {
			return true;
		}

		// Find a chest with items
		targetChestPos = findNearestAccessibleChest();
		if (targetChestPos == null) {
			searchCooldown = SEARCH_COOLDOWN_TIME + random.nextInt(20);
			return false;
		}

		return true;
	}

	@Override
	public boolean shouldContinueExecuting() {
		// Stop if a carrier appears nearby (every 20 ticks check)
		if (thief.ticksExisted % 20 == 0 && isCarrierNearby()) {
			return false;
		}

		if (currentState == State.IDLE) {
			return false;
		}

		// Check if current loot bag is valid
		if (lootBagPos != null && isValidLootBag(lootBagPos)) {
			if (hasReachedThiefLimit(lootBagPos)) {
				return false;
			}
			return true;
		}

		// Bag is missing or invalid. If we have items, try to switch to a hideout
		if (!carriedItems.isEmpty() || !thief.getHeldItemOffhand().isEmpty()) {
			BlockPos hideout = ThiefStashManager.get(world).getNearestHideout(world, thief.getPosition(), 500);
			if (hideout != null) {
				lootBagPos = hideout;
				// Ensure we are in the correct state to go to the bag
				if (currentState != State.DEPOSITING_ITEMS) {
					currentState = State.NAVIGATING_TO_BAG;
				}
				return true;
			}
		}

		return false;
	}

	private boolean isCarrierNearby() {
		double radius = 16.0D;
		List<EntityThief> nearbyThieves = world.getEntitiesWithinAABB(EntityThief.class, 
			new AxisAlignedBB(thief.getPosition()).grow(radius));

		for (EntityThief otherThief : nearbyThieves) {
			if (otherThief != thief && !otherThief.isDead) {
				ItemStack offhand = otherThief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
				if (!offhand.isEmpty() && offhand.getItem() instanceof ItemLootBag) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void startExecuting() {
		carriedItems.clear(); // Ensure it's clean (Fix 3)
		// Check if we started because we already have an item
		if (!thief.getHeldItemOffhand().isEmpty() && !thiefHasLootBag()) {
			carriedItems.add(thief.getHeldItemOffhand());
			currentState = State.NAVIGATING_TO_BAG;
		} else {
			currentState = State.NAVIGATING_TO_CHEST;
		}
		extractionTimer = 0;
	}

	@Override
	public void resetTask() {
		closeChest();
		clearHeldItem();
		
		// Drop carried items if any to prevent loss
		if (!carriedItems.isEmpty()) {
			for (ItemStack stack : carriedItems) {
				if (!stack.isEmpty()) {
					thief.entityDropItem(stack, 0.0F);
				}
			}
		}
		
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
			boolean pathFound = thief.getNavigator().tryMoveToXYZ(
				targetPos.x,
				targetPos.y,
				targetPos.z,
				speed
			);
			
			// If pathing fails or completes but we aren't there, and it's a master thief, force walking in straight line
			// This causes them to collide with walls and trigger their breach logic.
			if (thief instanceof EntityMasterThief && ((EntityMasterThief)thief).canBreach()) {
				if (!pathFound || thief.getNavigator().noPath()) {
					thief.getMoveHelper().setMoveTo(targetPos.x, targetPos.y, targetPos.z, speed);
				}
			}
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
				thief.setDropChance(net.minecraft.inventory.EntityEquipmentSlot.OFFHAND, 0.0F);
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
		BlockPos nearest = null;
		double minDistanceSq = BAG_SEARCH_RADIUS * BAG_SEARCH_RADIUS;

		for (TileEntity te : world.loadedTileEntityList) {
			if (te instanceof TileEntityLootBag) {
				if (Math.abs(te.getPos().getY() - thiefPos.getY()) <= 8) {
					double distSq = te.getDistanceSq(thiefPos.getX(), thiefPos.getY(), thiefPos.getZ());
					if (distSq <= minDistanceSq) {
						minDistanceSq = distSq;
						nearest = te.getPos();
					}
				}
			}
		}

		return nearest;
	}


	private List<BlockPos> scanForChests() {
		List<BlockPos> chests = new ArrayList<>();
		BlockPos thiefPos = thief.getPosition();
		double searchRadiusSq = CHEST_SEARCH_RADIUS * CHEST_SEARCH_RADIUS;

		for (TileEntity te : world.loadedTileEntityList) {
			if (Math.abs(te.getPos().getY() - thiefPos.getY()) <= 8) {
				double distSq = te.getDistanceSq(thiefPos.getX(), thiefPos.getY(), thiefPos.getZ());
				if (distSq <= searchRadiusSq) {
					if (PlayerBaseDetector.isValidInventory(te)) {
						if (hasAccessibleItems(te)) {
							chests.add(te.getPos());
						}
					}
				}
			}
		}

		return chests;
	}

	private BlockPos findNearestAccessibleChest() {
		List<BlockPos> chests = scanForChests();

		if (chests.isEmpty()) {
			return null;
		}

		BlockPos bestChest = null;
		float bestScore = -Float.MAX_VALUE;
		boolean isMaster = thief instanceof EntityMasterThief;

		for (BlockPos chestPos : chests) {
			TileEntity te = world.getTileEntity(chestPos);
			if (te == null) continue;

			float score = 0.0F;

			// 1. Capacity Base Score (larger inventories = more appealing)
			int slots = getSlotCount(te);
			score += slots * 2.0F;

			// 2. Exact vs Estimated Value
			if (isMaster) {
				// Master thieves have X-ray vision and know exactly what is inside
				score += calculateExactValue(te) * 5.0F;
			} else {
				// Regular thieves just look at how full the container is
				score += calculateEstimatedValue(te) * 3.0F;
			}

			// 3. Density/Cluster Bonus (highly appealing if surrounded by other containers)
			int neighbors = 0;
			for (BlockPos otherPos : chests) {
				if (otherPos != chestPos && chestPos.distanceSq(otherPos) <= 25.0D) { // Within 5 blocks
					neighbors++;
				}
			}
			score += neighbors * 15.0F;

			// 4. Distance Penalty (prefer closer targets if values are similar)
			double distSq = thief.getDistanceSq(chestPos);
			score -= (float) Math.sqrt(distSq) * 1.5F;

			// 5. Random Noise (prevents all thieves from swarming the exact same chest simultaneously)
			score += random.nextFloat() * 10.0F;

			if (score > bestScore) {
				bestScore = score;
				bestChest = chestPos;
			}
		}

		return bestChest != null ? bestChest : chests.get(random.nextInt(chests.size()));
	}

	private int getSlotCount(TileEntity te) {
		if (te.hasCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
			net.minecraftforge.items.IItemHandler handler = te.getCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
			return handler != null ? handler.getSlots() : 0;
		} else if (te instanceof IInventory) {
			return ((IInventory) te).getSizeInventory();
		}
		return 0;
	}

	private float calculateEstimatedValue(TileEntity te) {
		int filled = 0;
		if (te.hasCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
			net.minecraftforge.items.IItemHandler handler = te.getCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
			if (handler != null) {
				for (int i = 0; i < handler.getSlots(); i++) {
					if (!handler.getStackInSlot(i).isEmpty()) filled++;
				}
			}
		} else if (te instanceof IInventory) {
			IInventory inventory = (IInventory) te;
			for (int i = 0; i < inventory.getSizeInventory(); i++) {
				if (!inventory.getStackInSlot(i).isEmpty()) filled++;
			}
		}
		return filled; // Points per filled slot
	}

	private float calculateExactValue(TileEntity te) {
		float totalValue = 0.0F;
		if (te.hasCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
			net.minecraftforge.items.IItemHandler handler = te.getCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
			if (handler != null) {
				for (int i = 0; i < handler.getSlots(); i++) {
					ItemStack stack = handler.getStackInSlot(i);
					if (!stack.isEmpty()) {
						totalValue += PlayerBaseDetector.getItemValue(stack);
					}
				}
			}
		} else if (te instanceof IInventory) {
			IInventory inventory = (IInventory) te;
			for (int i = 0; i < inventory.getSizeInventory(); i++) {
				ItemStack stack = inventory.getStackInSlot(i);
				if (!stack.isEmpty()) {
					totalValue += PlayerBaseDetector.getItemValue(stack);
				}
			}
		}
		return totalValue;
	}

	private boolean hasAccessibleItems(TileEntity te) {
		if (te.hasCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
			net.minecraftforge.items.IItemHandler handler = te.getCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
			if (handler != null) {
				for (int i = 0; i < handler.getSlots(); i++) {
					if (!handler.getStackInSlot(i).isEmpty()) {
						return true;
					}
				}
			}
		} else if (te instanceof IInventory) {
			IInventory inventory = (IInventory) te;
			for (int i = 0; i < inventory.getSizeInventory(); i++) {
				if (!inventory.getStackInSlot(i).isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean extractItemsFromChest() {
		TileEntity te = world.getTileEntity(targetChestPos);
		if (te == null) {
			return false;
		}

		if (te.hasCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
			net.minecraftforge.items.IItemHandler handler = te.getCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
			if (handler != null) {
				List<Integer> occupiedSlots = new ArrayList<>();
				for (int i = 0; i < handler.getSlots(); i++) {
					if (!handler.getStackInSlot(i).isEmpty() && !handler.extractItem(i, 1, true).isEmpty()) {
						occupiedSlots.add(i);
					}
				}

				if (occupiedSlots.isEmpty()) {
					return false;
				}

				int slot = occupiedSlots.get(random.nextInt(occupiedSlots.size()));
				ItemStack stack = handler.getStackInSlot(slot);
				int maxExtract = thief instanceof EntityMasterThief ? 16 : 8;
				int extractCount = Math.min(stack.getCount(), 1 + random.nextInt(maxExtract));
				
				ItemStack extracted = handler.extractItem(slot, extractCount, false);
				if (!extracted.isEmpty()) {
					carriedItems.add(extracted);
					te.markDirty();
					return true;
				}
				return false;
			}
		}

		if (te instanceof IInventory) {
			IInventory inventory = (IInventory) te;
			List<Integer> occupiedSlots = new ArrayList<>();
			for (int i = 0; i < inventory.getSizeInventory(); i++) {
				if (!inventory.getStackInSlot(i).isEmpty()) {
					occupiedSlots.add(i);
				}
			}

			if (occupiedSlots.isEmpty()) {
				return false;
			}

			int slot = occupiedSlots.get(random.nextInt(occupiedSlots.size()));
			ItemStack stack = inventory.getStackInSlot(slot);
			int maxExtract = thief instanceof EntityMasterThief ? 16 : 8;
			int extractCount = Math.min(stack.getCount(), 1 + random.nextInt(maxExtract));
			
			ItemStack extracted = stack.splitStack(extractCount);
			carriedItems.add(extracted);
			inventory.markDirty();
			return true;
		}

		return false;
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
		TileEntity te = world.getTileEntity(pos);
		if (te instanceof TileEntityLootBag) {
			return !((TileEntityLootBag) te).isHideout();
		}
		return false;
	}

	private boolean hasReachedThiefLimit(BlockPos pos) {
		TileEntity te = world.getTileEntity(pos);
		if (te instanceof TileEntityLootBag) {
			TileEntityLootBag bag = (TileEntityLootBag) te;
			// Hideouts don't have a thief limit for depositing
			if (bag.isHideout()) {
				return false;
			}
			return bag.hasReachedThiefLimit();
		}
		return false;
	}

	private double getStealingSpeed() {
		double baseSpeed = 1.5D;
		if (thief instanceof EntityMasterThief) {
			baseSpeed *= ((EntityMasterThief) thief).getStealingSpeedMultiplier();
		}
		return baseSpeed;
	}

	private Vec3d getPositionInFrontOfChest(BlockPos chestPos) {
		// Get the chest's facing direction
		net.minecraft.block.state.IBlockState state = world.getBlockState(chestPos);
		EnumFacing facing = EnumFacing.NORTH; // default
		if (state.getBlock() instanceof net.minecraft.block.BlockChest) {
			facing = state.getValue(net.minecraft.block.BlockChest.FACING);
		}
		
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
			}
			chestOpened = true;
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
			}
			chestOpened = false;
		}
	}

	private boolean thiefHasLootBag() {
		ItemStack offhand = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		return !offhand.isEmpty() && offhand.getItem() instanceof com.windanesz.thieves.item.ItemLootBag;
	}
}
