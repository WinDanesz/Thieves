package com.windanesz.thieves.entity.ai;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;

/**
 * AI task for scout thieves to observe players from a safe distance.
 * Similar to EntityAIWatchClosest but maintains distance.
 */
public class ThiefScoutAIObserve extends EntityAIBase {

	private final EntityCreature entity;
	private final Class<? extends EntityLivingBase> targetClass;
	private final float maxDistance;
	private final float optimalDistance = 16.0F;
	private EntityLivingBase targetEntity;
	private int lookTime;

	public ThiefScoutAIObserve(EntityCreature entity, Class<? extends EntityLivingBase> targetClass, float maxDistance) {
		this.entity = entity;
		this.targetClass = targetClass;
		this.maxDistance = maxDistance;
		this.setMutexBits(2); // Looking flag
	}

	@Override
	public boolean shouldExecute() {
		// Find a nearby entity to observe
		this.targetEntity = this.entity.world.findNearestEntityWithinAABB(
			this.targetClass,
			this.entity.getEntityBoundingBox().grow(this.maxDistance, 3.0D, this.maxDistance),
			this.entity
		);

		return this.targetEntity != null;
	}

	@Override
	public boolean shouldContinueExecuting() {
		if (this.targetEntity == null) {
			return false;
		}

		if (!this.targetEntity.isEntityAlive()) {
			return false;
		}

		if (this.entity.getDistanceSq(this.targetEntity) > (this.maxDistance * this.maxDistance)) {
			return false;
		}

		return this.lookTime > 0;
	}

	@Override
	public void startExecuting() {
		// Set observation time (40-160 ticks = 2-8 seconds)
		this.lookTime = 40 + this.entity.getRNG().nextInt(120);
	}

	@Override
	public void resetTask() {
		this.targetEntity = null;
	}

	@Override
	public void updateTask() {
		// Look at the target
		this.entity.getLookHelper().setLookPositionWithEntity(
			this.targetEntity,
			(float) this.entity.getHorizontalFaceSpeed(),
			(float) this.entity.getVerticalFaceSpeed()
		);

		this.lookTime--;

		// Try to maintain optimal distance
		double distance = this.entity.getDistance(this.targetEntity);
		
		if (distance < optimalDistance * 0.7D && this.entity.getNavigator().noPath()) {
			// Too close, back away slightly
			moveAwayFromTarget();
		}
	}

	/**
	 * Moves the scout away from the target to maintain optimal distance.
	 */
	private void moveAwayFromTarget() {
		if (this.targetEntity == null) {
			return;
		}

		// Calculate direction away from target
		double dx = this.entity.posX - this.targetEntity.posX;
		double dz = this.entity.posZ - this.targetEntity.posZ;

		// Normalize and move
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance < 0.001D) {
			return;
		}

		dx /= distance;
		dz /= distance;

		// Move 4 blocks away
		double targetX = this.entity.posX + dx * 4.0D;
		double targetZ = this.entity.posZ + dz * 4.0D;
		double targetY = this.entity.world.getHeight((int) targetX, (int) targetZ);

		this.entity.getNavigator().tryMoveToXYZ(targetX, targetY, targetZ, 1.0D);
	}
}
