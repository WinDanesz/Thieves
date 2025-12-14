package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.item.ItemLootBag;
import com.windanesz.thieves.world.ThiefStashManager;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.items.ItemStackHandler;

/**
 * AI task for thieves to escape with a full loot bag.
 * Moves away from base, prefers darkness, creates stash and despawns when safe.
 */
public class ThiefAIEscapeWithLoot extends EntityAIBase {

	private final EntityThief thief;
	private final World world;
	private BlockPos escapeOrigin; // The base location to escape from
	private Vec3d escapeDirection;
	private int escapeTimer = 0;
	private boolean escapeInitiated = false; // Track if escape started (continue even if bag is lost)

	private static final int MAX_ESCAPE_TIME = 6000; // 5 minutes
	private static final double ESCAPE_DISTANCE = 80.0D; // Distance from base to be "safe"
	private static final double ESCAPE_SPEED = 1.5D;

	public ThiefAIEscapeWithLoot(EntityThief thief) {
		this.thief = thief;
		this.world = thief.world;
		this.setMutexBits(3);
	}

	@Override
	public boolean shouldExecute() {
		if (!hasLootBag()) {
			return false;
		}

		// Set escape origin if not set
		if (escapeOrigin == null) {
			escapeOrigin = thief.getPosition();
		}

		return true;
	}

	@Override
	public boolean shouldContinueExecuting() {
		// Continue until escape conditions are met
		// Once escape is initiated, continue even if loot bag is lost
		return escapeInitiated && !hasEscaped();
	}

	@Override
	public void startExecuting() {
		escapeTimer = 0;
		escapeInitiated = true;
		
		// Set escaping state to disable combat AI
		thief.setEscaping(true);
		
		// Clear attack target to prevent interference with escape
		thief.setAttackTarget(null);
		thief.setRevengeTarget(null);
		
		// Enable chunk loading to prevent despawn during escape
		thief.enableChunkLoading();
		
		// Calculate initial escape direction (away from origin)
		calculateEscapeDirection();
	}

	@Override
	public void resetTask() {
		thief.getNavigator().clearPath();
		thief.releaseChunkTicket();
		thief.setEscaping(false);
		escapeInitiated = false;
	}

	@Override
	public void updateTask() {
		escapeTimer++;

		// Check escape conditions
		if (hasEscaped()) {
			createStashAndDespawn();
			return;
		}

		// Update escape path when navigator has no path
		if (thief.getNavigator().noPath()) {
			findEscapePath();
		}

		// Look in escape direction
		if (escapeDirection != null) {
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
		// Escape if far enough from base or time limit reached
		if (escapeOrigin != null && thief.getDistanceSq(escapeOrigin) > ESCAPE_DISTANCE * ESCAPE_DISTANCE) {
			return true;
		}
		
		return escapeTimer >= MAX_ESCAPE_TIME;
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

			// Create stash
			ThiefStashManager stashManager = ThiefStashManager.get(world);
			BlockPos stashPos = stashManager.createStash(world, thief.getPosition(), lootContents, escapeOrigin);

			if (stashPos != null) {
				// Successfully created stash
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

	private boolean hasLootBag() {
		ItemStack offhand = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		return !offhand.isEmpty() && offhand.getItem() instanceof ItemLootBag;
	}
}
