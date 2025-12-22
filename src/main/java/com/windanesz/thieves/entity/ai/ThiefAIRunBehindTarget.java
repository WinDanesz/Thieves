package com.windanesz.thieves.entity.ai;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * AI task for thieves to run behind their attacker or target.
 * Useful for tactical positioning or fleeing to a safer spot behind the enemy.
 */
public class ThiefAIRunBehindTarget extends EntityAIBase {
	protected final EntityCreature creature;
	protected double speed;
	protected double randPosX;
	protected double randPosY;
	protected double randPosZ;
	private int panicTimer;
	private int cooldownTimer;

	public ThiefAIRunBehindTarget(EntityCreature creature, double speedIn) {
		this.creature = creature;
		this.speed = speedIn;
		this.setMutexBits(1);
	}

	/**
	 * Returns whether the EntityAIBase should begin execution.
	 */
	public boolean shouldExecute() {
		// Check cooldown first - if still cooling down, can't panic
		if (this.cooldownTimer > 0) {
			this.cooldownTimer--;
			return false;
		}

		// Goblin is ready to
		if (this.creature.getRevengeTarget() == null && !this.creature.isBurning()) {
			return false;
		} else {
			if (this.creature.isBurning()) {
				BlockPos blockpos = this.getRandPos(this.creature.world, this.creature, 5, 4);

				if (blockpos != null) {
					this.randPosX = (double) blockpos.getX();
					this.randPosY = (double) blockpos.getY();
					this.randPosZ = (double) blockpos.getZ();
					return true;
				}
			}
			return this.findRandomPosition();
		}
	}

	protected boolean findRandomPosition() {
		// Try to run behind the attacker (revenge target)
		if (this.creature.getRevengeTarget() != null) {
			Entity attacker = this.creature.getRevengeTarget();

			// Calculate vector from goblin to attacker
			double dx = attacker.posX - this.creature.posX;
			double dy = attacker.posY - this.creature.posY;
			double dz = attacker.posZ - this.creature.posZ;

			// Double the distance to go behind the attacker
			double targetX = attacker.posX + dx * 2;
			double targetY = attacker.posY + dy * 2;
			double targetZ = attacker.posZ + dz * 2;

			// Try to use this position behind attacker
			this.randPosX = targetX;
			this.randPosY = targetY;
			this.randPosZ = targetZ;
			return true;
		}

		// Fallback to random position if no revenge target
		Vec3d vec3d = RandomPositionGenerator.findRandomTarget(this.creature, 5, 4);

		if (vec3d == null) {
			return false;
		} else {
			this.randPosX = vec3d.x;
			this.randPosY = vec3d.y;
			this.randPosZ = vec3d.z;
			return true;
		}
	}

	/**
	 * Execute a one shot task or start executing a continuous task
	 */
	public void startExecuting() {
		this.creature.getNavigator().tryMoveToXYZ(this.randPosX, this.randPosY, this.randPosZ, this.speed);
		this.panicTimer = 0;
	}

	/**
	 * Returns whether an in-progress EntityAIBase should continue executing
	 */
	public boolean shouldContinueExecuting() {
		// Continue panicking for the full duration regardless of path validity
		return this.panicTimer < 30;
	}

	@Override
	public void updateTask() {
		this.panicTimer++;

		// Only look for a new path if current path is invalid/complete
		if (this.creature.getNavigator().noPath()) {
			if (this.findRandomPosition()) {
				this.creature.getNavigator().tryMoveToXYZ(this.randPosX, this.randPosY, this.randPosZ, this.speed);
			}
		}
	}

	@Override
	public void resetTask() {
		this.creature.getNavigator().clearPath();
		this.creature.setRevengeTarget(null); // Clear revenge target so it doesn't immediately panic again
		this.panicTimer = 0;
		this.cooldownTimer = 40; // Start 100-tick cooldown when panic ends
	}

	@Nullable
	private BlockPos getRandPos(World worldIn, Entity entityIn, int horizontalRange, int verticalRange) {
		BlockPos blockpos = new BlockPos(entityIn);
		int i = blockpos.getX();
		int j = blockpos.getY();
		int k = blockpos.getZ();
		float f = (float) (horizontalRange * horizontalRange * verticalRange * 2);
		BlockPos blockpos1 = null;
		BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();

		for (int l = i - horizontalRange; l <= i + horizontalRange; ++l) {
			for (int i1 = j - verticalRange; i1 <= j + verticalRange; ++i1) {
				for (int j1 = k - horizontalRange; j1 <= k + horizontalRange; ++j1) {
					blockpos$mutableblockpos.setPos(l, i1, j1);
					IBlockState iblockstate = worldIn.getBlockState(blockpos$mutableblockpos);

					if (iblockstate.getMaterial() == Material.WATER) {
						float f1 = (float) ((l - i) * (l - i) + (i1 - j) * (i1 - j) + (j1 - k) * (j1 - k));

						if (f1 < f) {
							f = f1;
							blockpos1 = new BlockPos(blockpos$mutableblockpos);
						}
					}
				}
			}
		}

		return blockpos1;
	}
}
