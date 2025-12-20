package com.windanesz.thieves.block;

import com.windanesz.thieves.Settings;
import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.world.ThiefStashManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * TileEntity for the loot bag block. Stores items without a GUI.
 * Right-click to add items, sneak+right-click to remove random items.
 */
public class TileEntityLootBag extends TileEntity implements ITickable {

	private ItemStackHandler inventory;
	private Random random = new Random();
	private boolean readyForPickup = false;
	
	// Hideout-specific fields
	private boolean isHideout = false;
	private int successfulRaids = 0;
	private int spawnCooldown = 0; // Cooldown in ticks before next spawn (5-10 minutes)

	public TileEntityLootBag() {
		this.inventory = new ItemStackHandler(Settings.lootBag.inventorySize) {
			@Override
			protected void onContentsChanged(int slot) {
				markDirty();
				updateReadyForPickup();
			}
		};
	}

	/**
	 * Adds an item stack to the loot bag inventory.
	 * @return True if items were added, false if bag is full.
	 */
	public boolean addItem(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		ItemStack remaining = stack.copy();

		// Try to insert into existing stacks first
		for (int i = 0; i < inventory.getSlots(); i++) {
			remaining = inventory.insertItem(i, remaining, false);
			if (remaining.isEmpty()) {
				return true;
			}
		}

		// If there's still items remaining, bag is full
		return remaining.isEmpty();
	}

	/**
	 * Removes a random item stack from the loot bag.
	 * @return The removed item stack, or ItemStack.EMPTY if bag is empty.
	 */
	public ItemStack removeRandomItem() {
		List<Integer> occupiedSlots = new ArrayList<>();

		// Find all occupied slots
		for (int i = 0; i < inventory.getSlots(); i++) {
			if (!inventory.getStackInSlot(i).isEmpty()) {
				occupiedSlots.add(i);
			}
		}

		if (occupiedSlots.isEmpty()) {
			return ItemStack.EMPTY;
		}

		// Pick a random occupied slot
		int randomSlotIndex = occupiedSlots.get(random.nextInt(occupiedSlots.size()));
		ItemStack stack = inventory.getStackInSlot(randomSlotIndex);
		inventory.setStackInSlot(randomSlotIndex, ItemStack.EMPTY);

		return stack;
	}

	/**
	 * Checks if the loot bag is full (all slots occupied).
	 */
	public boolean isFull() {
		for (int i = 0; i < inventory.getSlots(); i++) {
			if (inventory.getStackInSlot(i).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks if the bag has reached the maximum item count for thieves to stop stealing.
	 */
	public boolean hasReachedThiefLimit() {
		return getTotalItemCount() >= Settings.lootBag.maxItemCount;
	}

	/**
	 * Gets the number of occupied slots.
	 */
	public int getOccupiedSlots() {
		int count = 0;
		for (int i = 0; i < inventory.getSlots(); i++) {
			if (!inventory.getStackInSlot(i).isEmpty()) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Gets the total count of all items in the bag (including stackable items).
	 */
	public int getTotalItemCount() {
		int totalCount = 0;
		for (int i = 0; i < inventory.getSlots(); i++) {
			ItemStack stack = inventory.getStackInSlot(i);
			if (!stack.isEmpty()) {
				totalCount += stack.getCount();
			}
		}
		return totalCount;
	}

	/**
	 * Returns the inventory handler.
	 */
	public ItemStackHandler getInventory() {
		return inventory;
	}

	/**
	 * Checks if the bag is ready for pickup by thieves.
	 */
	public boolean isReadyForPickup() {
		return readyForPickup;
	}

	/**
	 * Updates the ready-for-pickup status based on fullness or thief limit.
	 */
	private void updateReadyForPickup() {
		readyForPickup = isFull() || hasReachedThiefLimit();
	}

	/**
	 * Transfers all contents to another ItemStackHandler.
	 */
	public void transferContentsTo(ItemStackHandler target) {
		for (int i = 0; i < inventory.getSlots(); i++) {
			ItemStack stack = inventory.getStackInSlot(i);
			if (!stack.isEmpty()) {
				// Try to insert into target
				for (int j = 0; j < target.getSlots(); j++) {
					stack = target.insertItem(j, stack, false);
					if (stack.isEmpty()) {
						break;
					}
				}
				inventory.setStackInSlot(i, ItemStack.EMPTY);
			}
		}
	}

	/**
	 * Gets all non-empty items in the bag.
	 */
	public List<ItemStack> getAllItems() {
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < inventory.getSlots(); i++) {
			ItemStack stack = inventory.getStackInSlot(i);
			if (!stack.isEmpty()) {
				items.add(stack.copy());
			}
		}
		return items;
	}

	/**
	 * Clears all items from the bag.
	 */
	public void clearInventory() {
		for (int i = 0; i < inventory.getSlots(); i++) {
			inventory.setStackInSlot(i, ItemStack.EMPTY);
		}
	}

	/**
	 * Checks if this loot bag is a hideout.
	 */
	public boolean isHideout() {
		return isHideout;
	}
	
	/**
	 * Sets this loot bag as a hideout. Should only be called when a thief establishes a hideout.
	 */
	public void setAsHideout(boolean hideout) {
		this.isHideout = hideout;
		markDirty();
	}
	
	/**
	 * Gets the number of successful raids stored at this hideout.
	 */
	public int getSuccessfulRaids() {
		return successfulRaids;
	}
	
	/**
	 * Increments the raid count when a thief deposits loot.
	 */
	public void incrementRaidCount() {
		this.successfulRaids++;
		// Reset spawn cooldown to 0 to allow thieves to spawn immediately after a successful raid
		this.spawnCooldown = 0;
		markDirty();
	}
	
	/**
	 * Sets the initial raid count (used when a hideout is first established).
	 */
	public void setSuccessfulRaids(int count) {
		this.successfulRaids = count;
		markDirty();
	}

	@Override
	public void update() {
		if (world == null || world.isRemote) {
			return;
		}
		
		// Hideout proximity spawning logic
		if (isHideout && spawnCooldown > 0) {
			spawnCooldown--;
		}
		
		if (isHideout && spawnCooldown <= 0) {
			checkForPlayerProximityAndSpawn();
		}
	}
	
	/**
	 * Checks for nearby players and spawns hostile thieves if found.
	 */
	private void checkForPlayerProximityAndSpawn() {
		// Check for players within 16-24 block radius
		double detectionRadius = 16.0D + random.nextDouble() * 8.0D; // 16-24 blocks
		
		AxisAlignedBB searchBox = new AxisAlignedBB(pos).grow(detectionRadius);
		List<EntityPlayer> nearbyPlayers = world.getEntitiesWithinAABB(EntityPlayer.class, searchBox,
				player -> player != null && !player.isCreative() && !player.isSpectator());
		
		if (!nearbyPlayers.isEmpty()) {
			// Spawn thieves based on raid count
			int thievesToSpawn = calculateThiefSpawnCount();
			
			for (int i = 0; i < thievesToSpawn; i++) {
				spawnHostileThief();
			}
			
			// Set cooldown: 5-10 minutes (6000-12000 ticks)
			spawnCooldown = 6000 + random.nextInt(6000);
			
			Thieves.LOGGER.info("Hideout at {} spawned {} thieves. Cooldown: {} ticks", pos, thievesToSpawn, spawnCooldown);
		}
	}
	
	/**
	 * Calculates how many thieves to spawn based on successful raids.
	 * Formula: min(5, 1 + successfulRaids / 3)
	 */
	private int calculateThiefSpawnCount() {
		return Math.min(5, 1 + successfulRaids / 3);
	}
	
	/**
	 * Spawns a single hostile thief near the hideout.
	 */
	private void spawnHostileThief() {
		// Try to find a valid spawn position within 8 blocks
		for (int attempt = 0; attempt < 20; attempt++) {
			double offsetX = (random.nextDouble() - 0.5D) * 16.0D;
			double offsetZ = (random.nextDouble() - 0.5D) * 16.0D;
			
			BlockPos spawnPos = pos.add(offsetX, 0, offsetZ);
			spawnPos = world.getTopSolidOrLiquidBlock(spawnPos);
			
			// Check if position is valid (not in liquid, has space above)
			if (world.getBlockState(spawnPos).getMaterial().isLiquid()) {
				continue;
			}
			
			if (!world.isAirBlock(spawnPos.up()) || !world.isAirBlock(spawnPos.up(2))) {
				continue;
			}
			
			// Spawn the thief
			EntityThief thief = new EntityThief(world);
			thief.setPosition(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D);
			
			// Equip weapon based on this hideout's raid count
			thief.equipWeaponBasedOnRaidCount(this.successfulRaids);
			
			// Make thief hostile
			thief.setNeutral(false);
			
			// Find nearest player to set as attack target
			EntityPlayer nearestPlayer = world.getClosestPlayerToEntity(thief, 32.0D);
			if (nearestPlayer != null && !nearestPlayer.isCreative() && !nearestPlayer.isSpectator()) {
				thief.setAttackTarget(nearestPlayer);
			}
			
			world.spawnEntity(thief);
			
			Thieves.LOGGER.debug("Spawned hostile thief at {}", spawnPos);
			return;
		}
		
		Thieves.LOGGER.warn("Failed to find valid spawn position near hideout at {}", pos);
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound compound) {
		super.writeToNBT(compound);
		compound.setTag("inventory", inventory.serializeNBT());
		compound.setBoolean("readyForPickup", readyForPickup);
		compound.setBoolean("isHideout", isHideout);
		compound.setInteger("successfulRaids", successfulRaids);
		compound.setInteger("spawnCooldown", spawnCooldown);
		return compound;
	}

	@Override
	public void readFromNBT(NBTTagCompound compound) {
		super.readFromNBT(compound);
		if (compound.hasKey("inventory")) {
			inventory.deserializeNBT(compound.getCompoundTag("inventory"));
		}
		readyForPickup = compound.getBoolean("readyForPickup");
		isHideout = compound.getBoolean("isHideout");
		successfulRaids = compound.getInteger("successfulRaids");
		spawnCooldown = compound.getInteger("spawnCooldown");
	}
	
	@Override
	public void invalidate() {
		super.invalidate();
		
		// If this was a hideout, unregister it from the world data
		if (isHideout && world != null && !world.isRemote) {
			ThiefStashManager manager = ThiefStashManager.get(world);
			manager.removeHideout(world.provider.getDimension(), pos);
			Thieves.LOGGER.info("Hideout at {} was destroyed and unregistered", pos);
		}
	}
}

