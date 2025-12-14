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
	private boolean escapeInitiated = false; // Track if escape started (continue even if bag is lost)
	private int pathRecalcCooldown = 0; // Cooldown to prevent constant path recalculation

	private static final int MAX_ESCAPE_TIME = 12000; // 10 minutes
	private static final double ESCAPE_DISTANCE = 200.0D; // Distance from base to be "safe"
	private static final double PLAYER_AVOID_DISTANCE = 128.0D; // Distance from players to despawn
	private static final double PLAYER_FLEE_DISTANCE = 12.0D; // Distance to actively flee from players
	private static final double ESCAPE_SPEED = 1.5D;
	private static final int PATH_RECALC_COOLDOWN = 60; // Ticks between path recalculations (3 seconds)

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
		stuckTimer = 0;
		escapeInitiated = true;
		
		// Enable chunk loading to prevent despawn during escape
		thief.enableChunkLoading();
		
		// Calculate initial escape direction (away from origin)
		calculateEscapeDirection();
	}

	@Override
	public void resetTask() {
		thief.getNavigator().clearPath();
		thief.releaseChunkTicket();
		escapeInitiated = false;
	}

	@Override
	public void updateTask() {
		escapeTimer++;
		if (pathRecalcCooldown > 0) {
			pathRecalcCooldown--;
		}

		// Check escape conditions
		if (hasEscaped()) {
			createStashAndDespawn();
			return;
		}

		// Update escape path only when needed
		if (thief.getNavigator().noPath() && pathRecalcCooldown <= 0) {
			findEscapePath();
			pathRecalcCooldown = PATH_RECALC_COOLDOWN;
			stuckTimer++;

			// If stuck for too long, give up
			if (stuckTimer > 200) { // 10 seconds stuck
				createStashAndDespawn();
				return;
			}
		} else if (!thief.getNavigator().noPath()) {
			stuckTimer = 0;
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

		// Path far ahead in escape direction (64 blocks)
		double targetX = thief.posX + escapeDirection.x * 64.0D;
		double targetZ = thief.posZ + escapeDirection.z * 64.0D;
		BlockPos targetPos = new BlockPos(targetX, thief.posY, targetZ);
		targetPos = world.getHeight(targetPos);

		// Try direct path first
		boolean success = thief.getNavigator().tryMoveToXYZ(targetPos.getX(), targetPos.getY(), targetPos.getZ(), ESCAPE_SPEED);

		// If direct path fails, use random position away from base
		if (!success) {
			Vec3d randomPos = RandomPositionGenerator.findRandomTargetBlockAwayFrom(
				thief,
				32,
				10,
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
			24,
			10,
			new Vec3d(player.posX, player.posY, player.posZ)
		);

		if (fleePos != null) {
			thief.getNavigator().tryMoveToXYZ(fleePos.x, fleePos.y, fleePos.z, ESCAPE_SPEED * 1.3D);
		}
	}

	private boolean hasEscaped() {
		// Check distance from origin
		if (escapeOrigin != null) {
			return true;
			//double distanceFromOrigin = thief.getDistanceSq(escapeOrigin);
			//if (distanceFromOrigin > ESCAPE_DISTANCE * ESCAPE_DISTANCE) {
				// Far enough from base, check for nearby players
			//	EntityPlayer nearestPlayer = world.getClosestPlayerToEntity(thief, PLAYER_AVOID_DISTANCE);
				//if (nearestPlayer == null) {
				//	return true; // Safe to despawn
				//}
			//}
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
