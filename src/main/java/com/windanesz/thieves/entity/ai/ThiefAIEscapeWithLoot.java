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
	private int stuckTimer = 0;

	private static final int MAX_ESCAPE_TIME = 12000; // 10 minutes
	private static final double ESCAPE_DISTANCE = 200.0D; // Distance from base to be "safe"
	private static final double PLAYER_AVOID_DISTANCE = 128.0D; // Distance from players to despawn
	private static final double ESCAPE_SPEED = 1.5D;

	public ThiefAIEscapeWithLoot(EntityThief thief) {
		this.thief = thief;
		this.world = thief.world;
		this.setMutexBits(7); // All movement flags
	}

	@Override
	public boolean shouldExecute() {
		// Only execute if thief has a loot bag
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
		return hasLootBag() && !hasEscaped();
	}

	@Override
	public void startExecuting() {
		escapeTimer = 0;
		stuckTimer = 0;
		
		// Calculate initial escape direction (away from origin)
		calculateEscapeDirection();
	}

	@Override
	public void resetTask() {
		thief.getNavigator().clearPath();
	}

	@Override
	public void updateTask() {
		escapeTimer++;

		// Check escape conditions
		if (hasEscaped()) {
			createStashAndDespawn();
			return;
		}

		// Update escape path
		if (thief.getNavigator().noPath()) {
			findEscapePath();
			stuckTimer++;

			// If stuck for too long, try teleporting or give up
			if (stuckTimer > 200) { // 10 seconds stuck
				createStashAndDespawn();
				return;
			}
		} else {
			stuckTimer = 0;
		}

		// Look in escape direction
		if (escapeDirection != null) {
			double lookX = thief.posX + escapeDirection.x * 10.0D;
			double lookZ = thief.posZ + escapeDirection.z * 10.0D;
			thief.getLookHelper().setLookPosition(lookX, thief.posY, lookZ, 10.0F, thief.getVerticalFaceSpeed());
		}

		// Avoid players actively
		EntityPlayer nearestPlayer = world.getClosestPlayerToEntity(thief, 32.0D);
		if (nearestPlayer != null) {
			fleeFromPlayer(nearestPlayer);
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

		// Try to find a position in escape direction
		double targetX = thief.posX + escapeDirection.x * 16.0D;
		double targetZ = thief.posZ + escapeDirection.z * 16.0D;

		// Prefer darker areas (lower light level)
		Vec3d targetPos = findDarkPath(targetX, targetZ);

		if (targetPos != null) {
			thief.getNavigator().tryMoveToXYZ(targetPos.x, targetPos.y, targetPos.z, ESCAPE_SPEED);
		} else {
			// Fallback: just move away from origin
			Vec3d randomPos = RandomPositionGenerator.findRandomTargetBlockAwayFrom(
				thief,
				16,
				7,
				new Vec3d(escapeOrigin.getX(), escapeOrigin.getY(), escapeOrigin.getZ())
			);

			if (randomPos != null) {
				thief.getNavigator().tryMoveToXYZ(randomPos.x, randomPos.y, randomPos.z, ESCAPE_SPEED);
			}
		}
	}

	private Vec3d findDarkPath(double targetX, double targetZ) {
		// Try several positions and pick the darkest one
		Vec3d darkest = null;
		int lowestLight = 16;

		for (int i = 0; i < 5; i++) {
			double offsetX = targetX + (world.rand.nextDouble() - 0.5D) * 10.0D;
			double offsetZ = targetZ + (world.rand.nextDouble() - 0.5D) * 10.0D;
			BlockPos checkPos = new BlockPos(offsetX, thief.posY, offsetZ);
			checkPos = world.getHeight(checkPos);

			int light = world.getLight(checkPos);
			if (light < lowestLight && world.getBlockState(checkPos.down()).isSideSolid(world, checkPos.down(), net.minecraft.util.EnumFacing.UP)) {
				lowestLight = light;
				darkest = new Vec3d(checkPos.getX() + 0.5D, checkPos.getY(), checkPos.getZ() + 0.5D);
			}
		}

		return darkest;
	}

	private void fleeFromPlayer(EntityPlayer player) {
		Vec3d fleePos = RandomPositionGenerator.findRandomTargetBlockAwayFrom(
			thief,
			16,
			7,
			new Vec3d(player.posX, player.posY, player.posZ)
		);

		if (fleePos != null) {
			thief.getNavigator().tryMoveToXYZ(fleePos.x, fleePos.y, fleePos.z, ESCAPE_SPEED * 1.2D);
		}
	}

	private boolean hasEscaped() {
		// Check distance from origin
		if (escapeOrigin != null) {
			double distanceFromOrigin = thief.getDistanceSq(escapeOrigin);
			if (distanceFromOrigin > ESCAPE_DISTANCE * ESCAPE_DISTANCE) {
				// Far enough from base, check for nearby players
				EntityPlayer nearestPlayer = world.getClosestPlayerToEntity(thief, PLAYER_AVOID_DISTANCE);
				if (nearestPlayer == null) {
					return true; // Safe to despawn
				}
			}
		}

		// Check time limit
		if (escapeTimer >= MAX_ESCAPE_TIME) {
			return true; // Escape timeout
		}

		return false;
	}

	private void createStashAndDespawn() {
		if (world.isRemote) {
			return;
		}

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
