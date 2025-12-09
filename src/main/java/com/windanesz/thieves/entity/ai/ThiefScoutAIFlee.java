package com.windanesz.thieves.entity.ai;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.Vec3d;

/**
 * AI task for scout thieves to flee from nearby players.
 */
public class ThiefScoutAIFlee extends EntityAIBase {

	private final EntityCreature entity;
	private final double speed;
	private final double fleeDistance;
	private EntityPlayer targetPlayer;
	private Vec3d fleePosition;

	public ThiefScoutAIFlee(EntityCreature entity, double speed, double fleeDistance) {
		this.entity = entity;
		this.speed = speed;
		this.fleeDistance = fleeDistance;
		this.setMutexBits(1); // Movement flag
	}

	@Override
	public boolean shouldExecute() {
		// Find nearest player
		this.targetPlayer = this.entity.world.getClosestPlayerToEntity(this.entity, this.fleeDistance);

		if (this.targetPlayer == null) {
			return false;
		}

		// Check if player is in creative/spectator (don't flee from them)
		if (this.targetPlayer.isCreative() || this.targetPlayer.isSpectator()) {
			return false;
		}

		// Find a position to flee to
		this.fleePosition = RandomPositionGenerator.findRandomTargetBlockAwayFrom(
			this.entity,
			16,
			7,
			new Vec3d(this.targetPlayer.posX, this.targetPlayer.posY, this.targetPlayer.posZ)
		);

		return this.fleePosition != null;
	}

	@Override
	public boolean shouldContinueExecuting() {
		// Continue fleeing if player is still too close
		return this.targetPlayer != null && 
			   this.entity.getDistanceSq(this.targetPlayer) < (this.fleeDistance * this.fleeDistance) &&
			   !this.entity.getNavigator().noPath();
	}

	@Override
	public void startExecuting() {
		// Start fleeing
		this.entity.getNavigator().tryMoveToXYZ(this.fleePosition.x, this.fleePosition.y, this.fleePosition.z, this.speed);
	}

	@Override
	public void resetTask() {
		this.targetPlayer = null;
		this.fleePosition = null;
	}

	@Override
	public void updateTask() {
		// Continuously update flee direction if player gets closer
		if (this.entity.getDistanceSq(this.targetPlayer) < (this.fleeDistance * 0.5D * this.fleeDistance * 0.5D)) {
			// Player is very close, flee faster
			Vec3d newFleePos = RandomPositionGenerator.findRandomTargetBlockAwayFrom(
				this.entity,
				16,
				7,
				new Vec3d(this.targetPlayer.posX, this.targetPlayer.posY, this.targetPlayer.posZ)
			);

			if (newFleePos != null) {
				this.fleePosition = newFleePos;
				this.entity.getNavigator().tryMoveToXYZ(newFleePos.x, newFleePos.y, newFleePos.z, this.speed * 1.2D);
			}
		}
	}
}
