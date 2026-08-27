package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.block.TileEntityLootBag;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.init.ModBlocks;
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
	private boolean escaped = false; // Flag to stop execution cleanly
	private boolean escapeInitiated = false;
	private static final double ESCAPE_SPEED = 1.0D;
	private static final int MAX_ESCAPE_TIME = 2400; // 2 minutes
	private static final int MAX_ESCAPE_TIME_WITH_HIDEOUT = 4800; // 4 minutes
	private static final int DEPOSIT_TIME = 40; // 2 seconds
	private static final double HIDEOUT_SEARCH_RADIUS = 200.0D;
	private static final int MAX_PATHFINDING_FAILURES = 10;
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

		return true;
	}

	@Override
	public boolean shouldContinueExecuting() {
		// Stop if we have successfully escaped
		if (escaped || thief.isDead) {
			return false;
		}
		
		// Stop if we lost our loot bag
		if (!hasLootBag()) {
			return false;
		}

		// Check timeout when escaping without hideout
		if (targetHideout == null && escapeTimer >= MAX_ESCAPE_TIME) {
			return false;
		}

		// Check timeout when navigating to hideout
		if (targetHideout != null && escapeTimer >= MAX_ESCAPE_TIME_WITH_HIDEOUT) {
			return false;
		}

		return true;
	}

	@Override
	public void startExecuting() {
		escapeTimer = 0;
		depositTimer = 0;
		pathfindingFailures = 0;
		escapeInitiated = true;
		escaped = false;
		
		// Set escape origin (where we're escaping from)
		escapeOrigin = thief.getPosition();
		
		// Set escaping state to disable combat AI
		thief.setEscaping(true);
		
		// Clear attack target to prevent interference with escape
		thief.setAttackTarget(null);
		thief.setRevengeTarget(null);
		
		// Enable chunk loading to prevent despawn during escape - removed per Fix 1
		// thief.enableChunkLoading();
		
		// Search for nearby hideout from current position
		ThiefStashManager stashManager = ThiefStashManager.get(world);
		targetHideout = stashManager.getNearestHideout(world, thief.getPosition(), HIDEOUT_SEARCH_RADIUS);
		
		if (targetHideout != null) {
			Thieves.LOGGER.debug("Thief found hideout at {}", targetHideout);
		} else {
			// Calculate initial escape direction (away from origin)
			calculateEscapeDirection();
		}
		
		// Immediately start navigating
		findEscapePath();
	}

	@Override
	public void resetTask() {
		thief.getNavigator().clearPath();
		// thief.releaseChunkTicket(); - removed per Fix 1
		thief.setEscaping(false);
		escapeInitiated = false;
		targetHideout = null;
		depositTimer = 0;
		pathfindingFailures = 0;

		// If the task ended because of timeout, force stash creation
		if (!escaped && !thief.isDead && hasLootBag()) {
			Thieves.LOGGER.info("Thief escape task ended via timeout. Creating stash and despawning.");
			createStashAndDespawn();
		}
	}

	@Override
	public void updateTask() {
		escapeTimer++;

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
				thief.getNavigator().tryMoveToXYZ(
					targetHideout.getX() + 0.5D,
					targetHideout.getY(),
					targetHideout.getZ() + 0.5D,
					ESCAPE_SPEED
				);
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
				
				thief.getNavigator().tryMoveToXYZ(waypointX, waypointY, waypointZ, ESCAPE_SPEED);
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

	private void createStashAndDespawn() {
		if (world.isRemote || thief.isDead) {
			return;
		}
		escaped = true;

		// Get loot bag from thief's inventory
		ItemStack bagItem = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		
		if (!bagItem.isEmpty() && bagItem.getItem() instanceof ItemLootBag) {
			// Get inventory from bag
			ItemStackHandler lootContents = ItemLootBag.getInventoryFromItem(bagItem);

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
		} else {
			// No loot bag? Just despawn
			thief.setDead();
		}
	}

	/**
	 * Deposits the thief's loot bag contents into an existing hideout.
	 */
	private void depositLootAtExistingHideout() {
		if (world.isRemote || thief.isDead) {
			return;
		}
		escaped = true;

		TileEntity te = world.getTileEntity(targetHideout);
		
		// If hideout is missing (destroyed), try to recreate it
		if (!(te instanceof TileEntityLootBag)) {
			// Check if we can place the bag here
			if (world.isAirBlock(targetHideout) || world.getBlockState(targetHideout).getBlock().isReplaceable(world, targetHideout)) {
				Thieves.LOGGER.info("Hideout at {} was destroyed, recreating it.", targetHideout);
				world.setBlockState(targetHideout, ModBlocks.LOOT_BAG.getDefaultState());
				
				te = world.getTileEntity(targetHideout);
				if (te instanceof TileEntityLootBag) {
					TileEntityLootBag newBag = (TileEntityLootBag) te;
					newBag.setAsHideout(true);
					// We don't know previous raid count, assume at least 1 since they were running to it
					newBag.setSuccessfulRaids(1);
				}
			} else {
				// Cannot recreate at exact spot, create new stash nearby
				Thieves.LOGGER.warn("Hideout at {} destroyed and obstructed. Creating new stash nearby.", targetHideout);
				targetHideout = null;
				createStashAndDespawn();
				return;
			}
		}
		
		// Re-fetch TE in case we just recreated it
		te = world.getTileEntity(targetHideout);
		
		if (!(te instanceof TileEntityLootBag)) {
			// Should act logically if recreation failed despite checks
			Thieves.LOGGER.error("Failed to restore hideout at {}.", targetHideout);
			targetHideout = null;
			createStashAndDespawn();
			return;
		}
		
		// Get thief's loot bag
		ItemStack thiefBagItem = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		if (!(thiefBagItem.getItem() instanceof ItemLootBag)) {
			thief.setDead();
			return;
		}
		
		// Extract contents from item
		ItemStackHandler thiefInventory = ItemLootBag.getInventoryFromItem(thiefBagItem);
		
		// Delegate to EntityThief to do the actual deposit and overflow chest logic (Fix 5)
		thief.depositLootAtHideout(targetHideout, thiefInventory);
		
		// Despawn after depositing
		thief.setDead();
	}

	private boolean hasLootBag() {
		ItemStack offhand = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		return !offhand.isEmpty() && offhand.getItem() instanceof ItemLootBag;
	}
}
