package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.entity.EntityThief;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITarget;

public class ThiefAIOwnerHurtTarget extends EntityAITarget {
	EntityThief tameableMob;
	EntityLivingBase attacker;
	private int timestamp;

	public ThiefAIOwnerHurtTarget(EntityThief tameableMob) {
		super(tameableMob, false);
		this.tameableMob = tameableMob;
		this.setMutexBits(1);
	}

	/**
	 * Returns whether the EntityAIBase should begin execution.
	 */
	public boolean shouldExecute() {
		// Don't attack for owner when holding an idol
		if (!this.tameableMob.hasOwner()) {
			return false;
		} else {
			EntityLivingBase entitylivingbase = this.tameableMob.getOwner();

			if (entitylivingbase == null) {
				return false;
			} else {
				this.attacker = entitylivingbase.getLastAttackedEntity();
				int i = entitylivingbase.getLastAttackedEntityTime();
				return i != this.timestamp && this.isSuitableTarget(this.attacker, false) && this.tameableMob.shouldAttackEntity(this.attacker, entitylivingbase);
			}
		}
	}

	/**
	 * Execute a one shot task or start executing a continuous task
	 */
	public void startExecuting() {
		this.taskOwner.setAttackTarget(this.attacker);
		EntityLivingBase entitylivingbase = this.tameableMob.getOwner();

		if (entitylivingbase != null) {
			this.timestamp = entitylivingbase.getLastAttackedEntityTime();
		}

		super.startExecuting();
	}
}