package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.entity.EntityThief;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITarget;

public class ThiefAIOwnerHurtByTarget extends EntityAITarget {
	EntityThief tameable;
	EntityLivingBase attacker;
	private int timestamp;

	public ThiefAIOwnerHurtByTarget(EntityThief theDefendingTameableIn) {
		super(theDefendingTameableIn, false);
		this.tameable = theDefendingTameableIn;
		this.setMutexBits(1);
	}

	/**
	 * Returns whether the EntityAIBase should begin execution.
	 */
	public boolean shouldExecute() {
		// Don't defend owner when holding an idol

		if (!this.tameable.hasOwner()) {
			return false;
		} else {
			EntityLivingBase entitylivingbase = this.tameable.getOwner();

			if (entitylivingbase == null) {
				return false;
			} else {
				this.attacker = entitylivingbase.getRevengeTarget();
				int i = entitylivingbase.getRevengeTimer();
				return i != this.timestamp && this.isSuitableTarget(this.attacker, false) && this.tameable.shouldAttackEntity(this.attacker, entitylivingbase);
			}
		}
	}

	/**
	 * Execute a one shot task or start executing a continuous task
	 */
	public void startExecuting() {
		this.taskOwner.setAttackTarget(this.attacker);
		EntityLivingBase entitylivingbase = this.tameable.getOwner();

		if (entitylivingbase != null) {
			this.timestamp = entitylivingbase.getRevengeTimer();
		}

		super.startExecuting();
	}
}