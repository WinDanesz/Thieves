package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.block.TileEntityLootBag;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.item.ItemLootBag;
import com.windanesz.thieves.world.ThiefStashManager;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.items.ItemStackHandler;

/**
 * AI task for thieves to escape with a full loot bag.
 * Searches for nearby hideouts within 200 blocks and deposits loot there.
 * If no hideout exists, escapes to safe distance, creates new hideout, and despawns.
 */
public class ThiefAIEscapeWithLoot extends EntityAIBase {

	private final EntityThief thief;
	private final World world;
	private BlockPos escapeOrigin; // The base location to escape from
	private BlockPos targetHideout; // Target hideout to navigate to
	private Vec3d escapeDirection;
	private int escapeTimer = 0;
	private int depositTimer = 0;
	private int pathfindingFailures = 0; // Track consecutive pathfinding failures
	private boolean escapeInitiated = false; // Track if escape started (continue even if bag is lost)

	private static final int MAX_ESCAPE_TIME = 1200; // 1 minute timeout when no hideout
	private static final int MAX_ESCAPE_TIME_WITH_HIDEOUT = 3600; // 3 minutes when navigating to hideout
	private static final double ESCAPE_DISTANCE = 50.0D; // Distance from base to be "safe"
	private static final double ESCAPE_SPEED = 1.5D;
	private static final double HIDEOUT_SEARCH_RADIUS = 200.0D;
	private static final int DEPOSIT_TIME = 40; // 2 seconds to deposit loot
	private static final int MAX_PATHFINDING_FAILURES = 100; // Abandon hideout after 100 failed pathfinding attempts

	public ThiefAIEscapeWithLoot(EntityThief thief) {
		this.thief = thief;
		this.world = thief.world;
		this.setMutexBits(3);
	}

	@Override
	public boolean shouldExecute() {
		// Only start if we have a loot bag and haven't started escaping yet
		if (!hasLootBag()) {
			// Reset escape flag if we lost the bag
			escapeInitiated = false;
			return false;
		}
		
		if (escapeInitiated) {
			return false;
		}

		Thieves.LOGGER.info("ThiefAIEscapeWithLoot shouldExecute = TRUE for thief at {}", thief.getPosition());
		return true;
	}

	@Override
	public boolean shouldContinueExecuting() {
		// Continue until escape conditions are met
		return !hasEscaped();
	}

	@Override
	public void startExecuting() {
		escapeTimer = 0;
		depositTimer = 0;
		pathfindingFailures = 0;
		escapeInitiated = true;
		
		// Set escape origin (where we're escaping from)
		escapeOrigin = thief.getPosition();
		
		// Set escaping state to disable combat AI
		thief.setEscaping(true);
		
		// Clear attack target to prevent interference with escape
		thief.setAttackTarget(null);
		thief.setRevengeTarget(null);
		
		// Enable chunk loading to prevent despawn during escape
		thief.enableChunkLoading();
		
		// Search for nearby hideout from current position
		ThiefStashManager stashManager = ThiefStashManager.get(world);
		targetHideout = stashManager.getNearestHideout(world, thief.getPosition(), HIDEOUT_SEARCH_RADIUS);
		
		if (targetHideout != null) {
			Thieves.LOGGER.info("Thief found hideout at {} (distance: {})", targetHideout, 
				Math.sqrt(thief.getDistanceSq(targetHideout)));
		} else {
			Thieves.LOGGER.info("No hideout found within {} blocks - will create new one", HIDEOUT_SEARCH_RADIUS);
			// Calculate initial escape direction (away from origin)
			calculateEscapeDirection();
		}
		
		// Immediately start navigating
		findEscapePath();
	}

	@Override
	public void resetTask() {
		thief.getNavigator().clearPath();
		thief.releaseChunkTicket();
		thief.setEscaping(false);
		escapeInitiated = false;
		targetHideout = null;
		depositTimer = 0;
		pathfindingFailures = 0;
	}

	@Override
	public void updateTask() {
		escapeTimer++;

		// Check escape conditions
		if (hasEscaped()) {
			Thieves.LOGGER.info("Thief has escaped! Creating stash and despawning");
			createStashAndDespawn();
			return;
		}
		
		// Log progress every 5 seconds when no hideout exists
		if (targetHideout == null && escapeTimer % 100 == 0) {
			double distFromOrigin = escapeOrigin != null ? Math.sqrt(thief.getDistanceSq(escapeOrigin)) : 0;
			Thieves.LOGGER.info("Thief escaping without hideout - Distance from origin: {}, Time: {}/{}", 
				(int)distFromOrigin, escapeTimer, MAX_ESCAPE_TIME);
		}

		// If at hideout, handle deposit
		if (targetHideout != null) {
			double distSq = thief.getDistanceSq(targetHideout);
			if (distSq < 9.0D) { // Within 3 blocks
				thief.getNavigator().clearPath();
				depositTimer++;
				
				// Look at hideout
				thief.getLookHelper().setLookPosition(
					targetHideout.getX() + 0.5D,
					targetHideout.getY() + 0.5D,
					targetHideout.getZ() + 0.5D,
					10.0F,
					thief.getVerticalFaceSpeed()
				);
				
				if (depositTimer >= DEPOSIT_TIME) {
					// Deposit complete
					depositLootAtExistingHideout();
					return;
				}
				return; // Don't continue moving while depositing
			} else {
				depositTimer = 0;
			}
		}

		// Update escape path when navigator has no path
		if (thief.getNavigator().noPath()) {
			pathfindingFailures++;
			
			// If hideout is unreachable after many attempts, abandon it and create new one
			if (targetHideout != null && pathfindingFailures >= MAX_PATHFINDING_FAILURES) {
				Thieves.LOGGER.warn("Hideout at {} is unreachable after {} attempts, abandoning and creating new hideout", 
					targetHideout, pathfindingFailures);
				targetHideout = null;
				pathfindingFailures = 0;
				calculateEscapeDirection();
			}
			
			if (pathfindingFailures % 20 == 0) { // Log every second
				Thieves.LOGGER.debug("Thief navigator has no path (attempt {}), finding escape path. Target hideout: {}", 
					pathfindingFailures, targetHideout);
			}
			findEscapePath();
		} else {
			// Successfully navigating, reset failure counter
			pathfindingFailures = 0;
		}

		// Look in appropriate direction
		if (targetHideout != null) {
			// Look at hideout
			thief.getLookHelper().setLookPosition(
				targetHideout.getX() + 0.5D,
				targetHideout.getY() + 0.5D,
				targetHideout.getZ() + 0.5D,
				10.0F,
				thief.getVerticalFaceSpeed()
			);
		} else if (escapeDirection != null) {
			// Look in escape direction
			double lookX = thief.posX + escapeDirection.x * 10.0D;
			double lookZ = thief.posZ + escapeDirection.z * 10.0D;
			thief.getLookHelper().setLookPosition(lookX, thief.posY, lookZ, 10.0F, thief.getVerticalFaceSpeed());
		}
	}

	private void calculateEscapeDirection() {
		if (escapeOrigin == null) {
			escapeOrigin = thief.getPosition();
		}

		// Direction away from origin
		double dx = thief.posX - escapeOrigin.getX();
		double dz = thief.posZ - escapeOrigin.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);

		if (distance < 0.1D) {
			// Pick random direction if at origin
			double angle = world.rand.nextDouble() * Math.PI * 2.0D;
			dx = Math.cos(angle);
			dz = Math.sin(angle);
		} else {
			dx /= distance;
			dz /= distance;
		}

		escapeDirection = new Vec3d(dx, 0, dz);
	}

	private void findEscapePath() {
		// If we have a target hideout, navigate in 15-block increments towards it
		if (targetHideout != null) {
			double distSq = thief.getDistanceSq(targetHideout);
			
			// If close enough, navigate directly to hideout
			if (distSq < 256.0D) { // Within 16 blocks
				boolean success = thief.getNavigator().tryMoveToXYZ(
					targetHideout.getX() + 0.5D,
					targetHideout.getY(),
					targetHideout.getZ() + 0.5D,
					ESCAPE_SPEED
				);
				if (!success && pathfindingFailures % 20 == 0) {
					Thieves.LOGGER.debug("Failed to pathfind to nearby hideout at {} from {}", targetHideout, thief.getPosition());
				}
			} else {
				// Calculate direction to hideout
				double dx = targetHideout.getX() - thief.posX;
				double dz = targetHideout.getZ() - thief.posZ;
				double distance = Math.sqrt(dx * dx + dz * dz);
				
				// Normalize and move 15 blocks in that direction
				dx = (dx / distance) * 15.0D;
				dz = (dz / distance) * 15.0D;
				
				double waypointX = thief.posX + dx;
				double waypointZ = thief.posZ + dz;
				double waypointY = thief.posY;
				
				boolean success = thief.getNavigator().tryMoveToXYZ(waypointX, waypointY, waypointZ, ESCAPE_SPEED);
				if (!success && pathfindingFailures % 20 == 0) {
					Thieves.LOGGER.debug("Failed to pathfind to waypoint towards hideout at {} from {}", 
						new BlockPos(waypointX, waypointY, waypointZ), thief.getPosition());
				}
			}
			return;
		} else if (escapeDirection != null) {
			// Move 15 blocks in the escape direction from current position
			double targetX = thief.posX + escapeDirection.x * 15.0D;
			double targetZ = thief.posZ + escapeDirection.z * 15.0D;
			double targetY = thief.posY; // Keep same Y level
			thief.getNavigator().tryMoveToXYZ(targetX, targetY, targetZ, ESCAPE_SPEED);
			return;
		}
		
		// Otherwise, escape away from origin
		if (escapeDirection == null) {
			calculateEscapeDirection();
		}

		// Try to move away from the base using random position generator
		Vec3d escapePos = RandomPositionGenerator.findRandomTargetBlockAwayFrom(
			thief,
			48,
			16,
			new Vec3d(escapeOrigin.getX(), escapeOrigin.getY(), escapeOrigin.getZ())
		);

		if (escapePos != null) {
			thief.getNavigator().tryMoveToXYZ(escapePos.x, escapePos.y, escapePos.z, ESCAPE_SPEED);
		}
	}

	private boolean hasEscaped() {
		// If navigating to hideout, check if we've arrived
		if (targetHideout != null) {
			double distSq = thief.getDistanceSq(targetHideout);
			if (distSq < 16.0D) { // Within 4 blocks of hideout
				return true;
			}
			
			// Longer timeout when navigating to existing hideout
			if (escapeTimer >= MAX_ESCAPE_TIME_WITH_HIDEOUT) {
				Thieves.LOGGER.info("Thief timeout while navigating to hideout at {}: {} ticks", targetHideout, escapeTimer);
				return true;
			}
			return false;
		}
		
		// No hideout - shorter timeout
		if (escapeTimer >= MAX_ESCAPE_TIME) {
			Thieves.LOGGER.info("Thief escaped via timeout (no hideout): {} ticks", escapeTimer);
			return true;
		}
		
		return false;
	}

	private void createStashAndDespawn() {
		if (world.isRemote) {
			return;
		}

		// Release chunk loading before despawning
		thief.releaseChunkTicket();

		// Get loot bag from thief's inventory
		ItemStack bagItem = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		
		if (!bagItem.isEmpty() && bagItem.getItem() instanceof ItemLootBag) {
			// Get inventory from bag
			ItemStackHandler lootContents = ItemLootBag.getInventoryFromItem(bagItem);

			if (targetHideout != null) {
				// This shouldn't happen since we deposit before reaching here
				// But handle it just in case
				Thieves.LOGGER.warn("Reached createStashAndDespawn with targetHideout still set");
				depositLootAtExistingHideout();
			} else {
				// No hideout was found - create new one at current position
				ThiefStashManager stashManager = ThiefStashManager.get(world);
				BlockPos stashPos = stashManager.createStash(world, thief.getPosition(), lootContents, escapeOrigin);

				if (stashPos != null) {
					// Mark the loot bag as a hideout and set initial raid count
					TileEntity te = world.getTileEntity(stashPos);
					if (te instanceof TileEntityLootBag) {
						TileEntityLootBag lootBag = (TileEntityLootBag) te;
						lootBag.setAsHideout(true);
						lootBag.setSuccessfulRaids(1); // First raid
					}
					
					// Register hideout in world data
					stashManager.registerHideout(world.provider.getDimension(), stashPos);
					
					// Clear thief's loot bag
					thief.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
					
					Thieves.LOGGER.info("Thief created new hideout at {}", stashPos);
					thief.setDead();
				} else {
					// Failed to create stash, drop the bag as item
					thief.entityDropItem(bagItem, 0.0F);
					thief.setDead();
				}
			}
		} else {
			// No loot bag? Just despawn
			thief.setDead();
		}
	}

	/**
	 * Deposits the thief's loot bag contents into an existing hideout.
	 */
	private void depositLootAtExistingHideout() {
		TileEntity te = world.getTileEntity(targetHideout);
		if (!(te instanceof TileEntityLootBag)) {
			Thieves.LOGGER.warn("Hideout at {} no longer has loot bag!", targetHideout);
			thief.setDead();
			return;
		}
		
		TileEntityLootBag hideoutBag = (TileEntityLootBag) te;
		
		// Get thief's loot bag
		ItemStack thiefBagItem = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		if (!(thiefBagItem.getItem() instanceof ItemLootBag)) {
			thief.setDead();
			return;
		}
		
		// Extract contents from item
		ItemStackHandler thiefInventory = ItemLootBag.getInventoryFromItem(thiefBagItem);
		
		// Try to transfer all items to hideout bag
		for (int i = 0; i < thiefInventory.getSlots(); i++) {
			ItemStack stack = thiefInventory.getStackInSlot(i);
			if (!stack.isEmpty()) {
				if (!hideoutBag.addItem(stack.copy())) {
					// Hideout bag is full, store in chest
					storeInNearbyChest(stack.copy());
				}
			}
		}
		
		// Increment raid count
		hideoutBag.incrementRaidCount();
		
		// Clear thief's loot bag
		thief.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
		
		Thieves.LOGGER.info("Thief deposited loot at hideout {}. Raid count: {}", 
			targetHideout, hideoutBag.getSuccessfulRaids());
		
		// Despawn after depositing
		thief.setEscaping(false);
		thief.setDead();
	}

	/**
	 * Stores overflow items in a chest near the hideout.
	 * Tries to find existing chests first, then places a new one if needed.
	 */
	private void storeInNearbyChest(ItemStack stack) {
		// Search for nearby chests within 5 blocks
		for (BlockPos checkPos : BlockPos.getAllInBox(
			targetHideout.add(-5, -3, -5),
			targetHideout.add(5, 3, 5))) {
			
			if (world.getBlockState(checkPos).getBlock() == Blocks.CHEST) {
				TileEntity te = world.getTileEntity(checkPos);
				if (te instanceof net.minecraft.tileentity.TileEntityChest) {
					net.minecraft.tileentity.TileEntityChest chest = (net.minecraft.tileentity.TileEntityChest) te;
					
					// Try to add to chest
					for (int i = 0; i < chest.getSizeInventory(); i++) {
						ItemStack chestStack = chest.getStackInSlot(i);
						if (chestStack.isEmpty()) {
							chest.setInventorySlotContents(i, stack);
							chest.markDirty();
							Thieves.LOGGER.debug("Stored overflow item in existing chest at {}", checkPos);
							return;
						} else if (chestStack.isItemEqual(stack) && chestStack.getCount() + stack.getCount() <= chestStack.getMaxStackSize()) {
							chestStack.grow(stack.getCount());
							chest.markDirty();
							Thieves.LOGGER.debug("Merged overflow item into existing chest at {}", checkPos);
							return;
						}
					}
				}
			}
		}
		
		// No chest with space found, place a new chest
		BlockPos chestPos = findAdjacentAirBlock(targetHideout);
		if (chestPos != null) {
			world.setBlockState(chestPos, Blocks.CHEST.getDefaultState());
			TileEntity te = world.getTileEntity(chestPos);
			if (te instanceof net.minecraft.tileentity.TileEntityChest) {
				net.minecraft.tileentity.TileEntityChest chest = (net.minecraft.tileentity.TileEntityChest) te;
				chest.setInventorySlotContents(0, stack);
				chest.markDirty();
				Thieves.LOGGER.info("Placed new chest at {} for overflow items", chestPos);
			}
		} else {
			Thieves.LOGGER.warn("Could not find space to place chest near hideout at {}", targetHideout);
		}
	}
	
	/**
	 * Finds an air block adjacent to or near the hideout position.
	 */
	private BlockPos findAdjacentAirBlock(BlockPos center) {
		// Check immediate adjacent blocks first
		for (BlockPos offset : new BlockPos[] {
			center.north(), center.south(), center.east(), center.west(),
			center.up(), center.down()
		}) {
			if (world.isAirBlock(offset) && canPlaceChest(offset)) {
				return offset;
			}
		}
		
		// Check in a 3x3x3 area
		for (BlockPos checkPos : BlockPos.getAllInBox(
			center.add(-2, -2, -2),
			center.add(2, 2, 2))) {
			
			if (world.isAirBlock(checkPos) && canPlaceChest(checkPos)) {
				return checkPos;
			}
		}
		
		return null;
	}
	
	/**
	 * Checks if a chest can be placed at this position.
	 */
	private boolean canPlaceChest(BlockPos pos) {
		// Check if block below is solid
		BlockPos below = pos.down();
		IBlockState belowState = world.getBlockState(below);
		return belowState.isSideSolid(world, below, net.minecraft.util.EnumFacing.UP);
	}

	private boolean hasLootBag() {
		ItemStack offhand = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		return !offhand.isEmpty() && offhand.getItem() instanceof ItemLootBag;
	}
}
