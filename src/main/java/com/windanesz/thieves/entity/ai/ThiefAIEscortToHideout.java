package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.block.TileEntityLootBag;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.item.ItemLootBag;
import com.windanesz.thieves.world.ThiefStashManager;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * AI task for robbery thieves without loot bags to return to nearest hideout or despawn.
 * Only active for thieves marked as robbery thieves.
 */
public class ThiefAIEscortToHideout extends EntityAIBase {

	private final EntityThief thief;
	private final World world;
	private BlockPos targetHideout;
	private EntityThief thiefToFollow;
	private int despawnTimer = 0;
	private int travelTimer = 0;
	
	private static final double HIDEOUT_SEARCH_RADIUS = 500.0D;
	private static final double THIEF_SEARCH_RADIUS = 32.0D;
	private static final int DESPAWN_DELAY = 300; // 15 seconds before despawning if no hideout
	private static final int MAX_TRAVEL_TIME = 2400; // 2 minutes to reach target before forced despawn
	private static final double MOVE_SPEED = 1.7D;
	private static final double FOLLOW_DISTANCE = 8.0D;
	private int searchCooldown = 0;
	
	public ThiefAIEscortToHideout(EntityThief thief) {
		this.thief = thief;
		this.world = thief.world;
		this.setMutexBits(3); // Movement mutex
	}
	
	@Override
	public boolean shouldExecute() {
		// Only execute if this is a robbery thief without a loot bag
		if (!thief.isRobberyThief()) {
			return false;
		}
		
		// Check if thief has a loot bag
		if (hasLootBag()) {
			return false;
		}
		
		// Don't interfere if already escaping
		if (thief.isEscaping()) {
			return false;
		}
		
		// Search for nearest hideout or thief with loot
		if (targetHideout == null) {
			ThiefStashManager manager = ThiefStashManager.get(world);
			targetHideout = manager.getNearestHideout(world, thief.getPosition(), HIDEOUT_SEARCH_RADIUS);
		}
		
		if (thiefToFollow == null) {
			thiefToFollow = findNearbyThiefWithLootBag();
		}
		
		// Always execute - will search for targets or despawn
		return true;
	}
	
	@Override
	public boolean shouldContinueExecuting() {
		if (thief.isDead) return false;
		if (hasLootBag()) return false;
		if (thief.isEscaping()) return false;
		if (thiefToFollow != null && (thiefToFollow.isDead || !thiefToFollow.isEntityAlive())) return false;
		
		return true;
	}
	
	@Override
	public void startExecuting() {
		despawnTimer = 0;
		travelTimer = 0;
		thiefToFollow = null;
		
		if (targetHideout != null) {
			Thieves.LOGGER.info("Robbery thief at {} returning to hideout at {}", 
				thief.getPosition(), targetHideout);
		} else {
			// Check for nearby thief with loot bag
			thiefToFollow = findNearbyThiefWithLootBag();
			
			if (thiefToFollow != null) {
				Thieves.LOGGER.info("Robbery thief at {} will follow thief with loot bag at {}", 
					thief.getPosition(), thiefToFollow.getPosition());
			} else {
				Thieves.LOGGER.info("Robbery thief at {} has no nearby hideout or thief with loot, will despawn in {} seconds", 
					thief.getPosition(), DESPAWN_DELAY / 20.0);
			}
		}
	}
	
	@Override
	public void resetTask() {
		targetHideout = null;
		thiefToFollow = null;
		despawnTimer = 0;
		travelTimer = 0;
		searchCooldown = 0;
		thief.getNavigator().clearPath();
	}
	
	private void dropHeldItems() {
		// Drop offhand item (likely stolen loot or loot bag)
		// Don't drop shields as they are part of the thief's equipment
		ItemStack offhand = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		if (!offhand.isEmpty() && offhand.getItem() != Items.SHIELD) {
			thief.entityDropItem(offhand, 0.0F);
			thief.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
		}
		
		// Do not drop mainhand item (weapon) as escorts should keep their gear
	}
	
	@Override
	public void updateTask() {
		// Periodically retry searching for targets
		searchCooldown++;
		if (searchCooldown >= 40) { // Every 2 seconds
			searchCooldown = 0;
			
			if (targetHideout == null) {
				ThiefStashManager manager = ThiefStashManager.get(world);
				targetHideout = manager.getNearestHideout(world, thief.getPosition(), HIDEOUT_SEARCH_RADIUS);
				if (targetHideout != null) {
					Thieves.LOGGER.info("Robbery thief at {} found hideout at {} during search", 
						thief.getPosition(), targetHideout);
					despawnTimer = 0;
					travelTimer = 0;
				}
			}
			
			if (thiefToFollow == null) {
				thiefToFollow = findNearbyThiefWithLootBag();
				if (thiefToFollow != null) {
					Thieves.LOGGER.info("Robbery thief at {} found thief with loot at {} during search", 
						thief.getPosition(), thiefToFollow.getPosition());
					despawnTimer = 0;
					travelTimer = 0;
				}
			}
		}

		// Priority 1: Follow hideout path if we have one
		if (targetHideout != null) {
			travelTimer++;
			if (travelTimer >= MAX_TRAVEL_TIME) {
				Thieves.LOGGER.info("Robbery thief timed out reaching hideout at {}, despawning", targetHideout);
				dropHeldItems();
				thief.setDead();
				return;
			}
			navigateToHideout();
			return;
		}
		
		// Priority 2: Follow a thief with loot bag
		if (thiefToFollow != null) {
			// Check if thief still has loot bag and is alive
			if (thiefToFollow.isDead || !hasLootBag(thiefToFollow)) {
				Thieves.LOGGER.debug("Thief to follow no longer has loot bag or is dead, searching for new target or hideout");
				thiefToFollow = findNearbyThiefWithLootBag();
				travelTimer = 0; // Reset travel timer as we search for new target
				
				// If no thief found, search for a hideout instead
				if (thiefToFollow == null && targetHideout == null) {
					ThiefStashManager manager = ThiefStashManager.get(world);
					targetHideout = manager.getNearestHideout(world, thief.getPosition(), HIDEOUT_SEARCH_RADIUS);
					
					if (targetHideout != null) {
						Thieves.LOGGER.info("Followed thief disappeared, robbery thief at {} now heading to hideout at {}", 
							thief.getPosition(), targetHideout);
						despawnTimer = 0; // Reset despawn timer
						travelTimer = 0; // Reset travel timer
						return;
					}
				}
			}
			
			if (thiefToFollow != null) {
				travelTimer++;
				if (travelTimer >= MAX_TRAVEL_TIME) {
					Thieves.LOGGER.info("Robbery thief timed out following thief, despawning");
					dropHeldItems();
					thief.setDead();
					return;
				}
				followThief();
				return;
			}
		}
		
		// Priority 3: No hideout and no thief to follow - despawn after delay
		despawnTimer++;
		
		if (despawnTimer >= DESPAWN_DELAY) {
			Thieves.LOGGER.info("Robbery thief at {} despawning (no hideout or thief with loot found after {} seconds)", 
				thief.getPosition(), DESPAWN_DELAY / 20.0);
			dropHeldItems();
			thief.setDead();
		}
	}
	
	private void navigateToHideout() {
		double distSq = thief.getDistanceSq(targetHideout);
		
		if (distSq < 16.0D) {
			// Reached hideout location
			TileEntity te = world.getTileEntity(targetHideout);
			if (te instanceof TileEntityLootBag) {
				// Valid hideout, despawn
				Thieves.LOGGER.info("Robbery thief reached hideout at {}, despawning", targetHideout);
				dropHeldItems();
				thief.setDead();
			} else {
				// Hideout missing/destroyed, search for another one
				Thieves.LOGGER.warn("Hideout at {} is missing, robbery thief searching for new target", targetHideout);
				targetHideout = null;
			}
		} else {
			// Continue moving to hideout
			if (thief.getNavigator().noPath()) {
				// Navigate in 15-block increments (same as escape AI)
				if (distSq < 256.0D) { // Within 16 blocks
					thief.getNavigator().tryMoveToXYZ(
						targetHideout.getX() + 0.5D,
						targetHideout.getY(),
						targetHideout.getZ() + 0.5D,
						MOVE_SPEED
					);
				} else {
					// Calculate direction to hideout
					double dx = targetHideout.getX() - thief.posX;
					double dz = targetHideout.getZ() - thief.posZ;
					double distance = Math.sqrt(dx * dx + dz * dz);
					
					// Normalize and move 15 blocks in that direction
					dx = (dx / distance) * 15.0D;
					dz = (dz / distance) * 15.0D;
					
					double waypointX = thief.posX + dx;
					double waypointZ = thief.posZ + dz;
					double waypointY = thief.posY;
					
					thief.getNavigator().tryMoveToXYZ(waypointX, waypointY, waypointZ, MOVE_SPEED);
				}
			}
			
			// Look at hideout
			thief.getLookHelper().setLookPosition(
				targetHideout.getX() + 0.5D,
				targetHideout.getY() + 0.5D,
				targetHideout.getZ() + 0.5D,
				10.0F,
				thief.getVerticalFaceSpeed()
			);
		}
	}
	
	private void followThief() {
		double distSq = thief.getDistanceSq(thiefToFollow);
		
		// Follow at a reasonable distance
		if (distSq > FOLLOW_DISTANCE * FOLLOW_DISTANCE) {
			if (thief.getNavigator().noPath()) {
				thief.getNavigator().tryMoveToEntityLiving(thiefToFollow, MOVE_SPEED);
			}
		} else {
			// Close enough, just look at the thief
			thief.getNavigator().clearPath();
		}
		
		// Look at the thief we're following
		thief.getLookHelper().setLookPositionWithEntity(thiefToFollow, 10.0F, thief.getVerticalFaceSpeed());
	}
	
	private EntityThief findNearbyThiefWithLootBag() {
		AxisAlignedBB searchBox = new AxisAlignedBB(thief.getPosition()).grow(THIEF_SEARCH_RADIUS);
		List<EntityThief> nearbyThieves = world.getEntitiesWithinAABB(EntityThief.class, searchBox,
			t -> t != null && !t.isDead && t != thief && hasLootBag(t));
		
		if (!nearbyThieves.isEmpty()) {
			// Return the closest thief with a loot bag
			return nearbyThieves.stream()
				.min((t1, t2) -> Double.compare(thief.getDistanceSq(t1), thief.getDistanceSq(t2)))
				.orElse(null);
		}
		
		return null;
	}
	
	private boolean hasLootBag() {
		ItemStack offhand = thief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		return !offhand.isEmpty() && offhand.getItem() instanceof ItemLootBag;
	}
	
	private boolean hasLootBag(EntityThief targetThief) {
		ItemStack offhand = targetThief.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
		return !offhand.isEmpty() && offhand.getItem() instanceof ItemLootBag;
	}
}
