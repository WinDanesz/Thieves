package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.init.ModItems;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.pathfinding.PathNavigate;

import java.util.List;

/**
 * AI task for thieves to steal specific dropped items (like idols) from the ground.
 * Currently configured to search for and pick up specific item types.
 */
public class ThiefAISteal extends EntityAIBase {
	private static final double DETECTION_RANGE = 6.0D;
	private static final int SEARCH_INTERVAL = 20; // Search every second (20 ticks)
	private final EntityThief goblin;
	private final PathNavigate navigator;
	private EntityItem targetIdol;
	private int searchCooldown;

	public ThiefAISteal(EntityThief goblin) {
		this.goblin = goblin;
		this.navigator = goblin.getNavigator();
		this.setMutexBits(3); // Movement mutex
		this.searchCooldown = 0;
	}

	@Override
	public boolean shouldExecute() {
		// Don't seek idols if already holding one

		// Cooldown between searches to avoid excessive entity scanning
		if (this.searchCooldown > 0) {
			this.searchCooldown--;
			return false;
		}

		// Search for nearby dropped goblin idols
		this.searchCooldown = SEARCH_INTERVAL;
		this.targetIdol = this.findNearestIdol();

		return this.targetIdol != null;
	}

	@Override
	public boolean shouldContinueExecuting() {
		// Stop if we picked up an idol
		return false;
	}

	@Override
	public void startExecuting() {
		if (this.targetIdol != null) {
			this.navigator.tryMoveToEntityLiving(this.targetIdol, 1.2D);
		}
	}

	@Override
	public void resetTask() {
		this.targetIdol = null;
		this.navigator.clearPath();
	}

	@Override
	public void updateTask() {
		if (this.targetIdol != null && !this.targetIdol.isDead) {
			this.goblin.getLookHelper().setLookPositionWithEntity(this.targetIdol, 10.0F,
					(float) this.goblin.getVerticalFaceSpeed());

			// Update path periodically
			if (this.goblin.getDistanceSq(this.targetIdol) > 1.5D) {
				this.navigator.tryMoveToEntityLiving(this.targetIdol, 1.2D);
			}
		}
	}

	private EntityItem findNearestIdol() {
		List<EntityItem> nearbyItems = this.goblin.world.getEntitiesWithinAABB(
				EntityItem.class,
				this.goblin.getEntityBoundingBox().grow(DETECTION_RANGE, 2.0D, DETECTION_RANGE)
		);

		EntityItem closestIdol = null;
		double closestDistance = Double.MAX_VALUE;

		for (EntityItem itemEntity : nearbyItems) {
//			if (itemEntity.getItem().getItem() == ModItems.goblin_idol) {
//				double distance = this.goblin.getDistanceSq(itemEntity);
//				if (distance < closestDistance) {
//					closestDistance = distance;
//					closestIdol = itemEntity;
//				}
//			}
		}

		return closestIdol;
	}
}
