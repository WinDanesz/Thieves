package com.windanesz.thieves.block;

import com.windanesz.thieves.Settings;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
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

	@Override
	public void update() {
		// Tick logic if needed (e.g., particles when full)
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound compound) {
		super.writeToNBT(compound);
		compound.setTag("inventory", inventory.serializeNBT());
		compound.setBoolean("readyForPickup", readyForPickup);
		return compound;
	}

	@Override
	public void readFromNBT(NBTTagCompound compound) {
		super.readFromNBT(compound);
		if (compound.hasKey("inventory")) {
			inventory.deserializeNBT(compound.getCompoundTag("inventory"));
		}
		readyForPickup = compound.getBoolean("readyForPickup");
	}
}

