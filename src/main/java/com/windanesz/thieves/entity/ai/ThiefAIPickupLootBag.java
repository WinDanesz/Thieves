package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.block.TileEntityLootBag;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.init.ModBlocks;
import com.windanesz.thieves.item.ItemLootBag;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * AI task for thieves to pick up a full loot bag and convert it to an item.
 */
public class ThiefAIPickupLootBag extends EntityAIBase {

	private final EntityThief thief;
	private final World world;
	private BlockPos targetBagPos;
	private int pickupTimer = 0;

	private static final int BAG_SEARCH_RADIUS = 32;
	private static final int PICKUP_TIME = 30; // 1.5 seconds to pick up

	public ThiefAIPickupLootBag(EntityThief thief) {
		this.thief = thief;
		this.world = thief.world;
		this.setMutexBits(3); // Movement mutex
	}

	@Override
	public boolean shouldExecute() {
		// Check if thief already has a loot bag
		if (hasLootBag()) {
			return false;
		}

		// Find a full loot bag
		targetBagPos = findFullLootBag();

		return targetBagPos != null;
	}

	@Override
	public boolean shouldContinueExecuting() {
		// Continue until pickup is complete or bag is gone
		return targetBagPos != null && 
			   isValidFullLootBag(targetBagPos) &&
			   !hasLootBag();
	}

	@Override
	public void startExecuting() {
		pickupTimer = 0;
	}

	@Override
	public void resetTask() {
		targetBagPos = null;
		pickupTimer = 0;
		thief.getNavigator().clearPath();
	}

	@Override
	public void updateTask() {
		if (targetBagPos == null) {
			return;
		}

		// Look at bag
		thief.getLookHelper().setLookPosition(
			targetBagPos.getX() + 0.5,
			targetBagPos.getY() + 0.5,
			targetBagPos.getZ() + 0.5,
			10.0F,
			thief.getVerticalFaceSpeed()
		);

		double distance = thief.getDistanceSq(targetBagPos);

		if (distance < 4.0D) {
			// Close enough, start pickup
			thief.getNavigator().clearPath();
			pickupTimer++;

			if (pickupTimer >= PICKUP_TIME) {
				// Pickup complete
				pickupLootBag();
			}
		} else {
			// Navigate to bag
			thief.getNavigator().tryMoveToXYZ(
				targetBagPos.getX() + 0.5,
				targetBagPos.getY(),
				targetBagPos.getZ() + 0.5,
				1.2D
			);
			pickupTimer = 0;
		}
	}

	private BlockPos findFullLootBag() {
		BlockPos thiefPos = thief.getPosition();

		for (int x = -BAG_SEARCH_RADIUS; x <= BAG_SEARCH_RADIUS; x++) {
			for (int z = -BAG_SEARCH_RADIUS; z <= BAG_SEARCH_RADIUS; z++) {
				for (int y = -8; y <= 8; y++) {
					BlockPos checkPos = thiefPos.add(x, y, z);
					
					if (isValidFullLootBag(checkPos)) {
						return checkPos;
					}
				}
			}
		}

		return null;
	}

	private boolean isValidFullLootBag(BlockPos pos) {
		if (world.getBlockState(pos).getBlock() != ModBlocks.LOOT_BAG) {
			return false;
		}

		TileEntity te = world.getTileEntity(pos);
		if (!(te instanceof TileEntityLootBag)) {
			return false;
		}

		TileEntityLootBag lootBag = (TileEntityLootBag) te;
		
		// Don't pick up hideout bags - they are meant to stay in place
		if (lootBag.isHideout()) {
			return false;
		}
		
		return lootBag.isReadyForPickup(); // Only pick up full bags
	}

	private void pickupLootBag() {
		TileEntity te = world.getTileEntity(targetBagPos);
		if (!(te instanceof TileEntityLootBag)) {
			return;
		}

		TileEntityLootBag lootBag = (TileEntityLootBag) te;

		// Create item from tile entity
		ItemStack bagItem = ItemLootBag.fromTileEntity(lootBag);

		// Give to thief (in offhand)
		thief.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, bagItem);

		// Remove block
		world.setBlockToAir(targetBagPos);

		// Play sound
		world.playSound(null, targetBagPos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 1.0F, 1.0F);

		// Clear attack target to allow escape AI to take control
		thief.setAttackTarget(null);
		thief.setRevengeTarget(null);
		
		// Mark that thief now has the loot
		thief.setNeutral(true); // Use existing neutral flag to indicate thief has loot
	}

	private boolean hasLootBag() {
		ItemStack offhand = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		return !offhand.isEmpty() && offhand.getItem() instanceof ItemLootBag;
	}
}
