package com.windanesz.thieves.item;

import com.windanesz.thieves.Settings;
import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.block.TileEntityLootBag;
import com.windanesz.thieves.init.ModBlocks;
import com.windanesz.thieves.init.ModItems;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.items.ItemStackHandler;

/**
 * Item form of the loot bag, carried by thieves when they pick up a full bag.
 */
public class ItemLootBag extends Item {

	public ItemLootBag() {
		setMaxStackSize(1);
		setCreativeTab(CreativeTabs.MISC);
		setRegistryName(Thieves.MODID, "loot_bag");
		setTranslationKey(Thieves.MODID + ".loot_bag");
	}

	@Override
	public EnumActionResult onItemUse(EntityPlayer player, World worldIn, BlockPos pos, EnumHand hand, 
									  EnumFacing facing, float hitX, float hitY, float hitZ) {
		if (worldIn.isRemote) {
			return EnumActionResult.SUCCESS;
		}

		ItemStack stack = player.getHeldItem(hand);
		BlockPos placePos = pos.offset(facing);

		// Check if the position is valid for placement
		if (!worldIn.isAirBlock(placePos)) {
			return EnumActionResult.FAIL;
		}

		// Place the loot bag block
		worldIn.setBlockState(placePos, ModBlocks.LOOT_BAG.getDefaultState());

		// Transfer NBT data to tile entity
		TileEntity te = worldIn.getTileEntity(placePos);
		if (te instanceof TileEntityLootBag && stack.hasTagCompound()) {
			TileEntityLootBag lootBag = (TileEntityLootBag) te;
			NBTTagCompound nbt = stack.getTagCompound();
			if (nbt.hasKey("inventory")) {
				lootBag.getInventory().deserializeNBT(nbt.getCompoundTag("inventory"));
			}
			lootBag.markDirty();
		}

		// Consume the item
		stack.shrink(1);

		return EnumActionResult.SUCCESS;
	}

	/**
	 * Creates an ItemLootBag from a TileEntityLootBag, preserving contents.
	 */
	public static ItemStack fromTileEntity(TileEntityLootBag lootBag) {
		ItemStack stack = new ItemStack(ModItems.LOOT_BAG_ITEM);
		NBTTagCompound nbt = new NBTTagCompound();
		nbt.setTag("inventory", lootBag.getInventory().serializeNBT());
		stack.setTagCompound(nbt);
		return stack;
	}

	/**
	 * Gets the inventory from an ItemLootBag stack.
	 */
	public static ItemStackHandler getInventoryFromItem(ItemStack stack) {
		ItemStackHandler handler = new ItemStackHandler(Settings.lootBag.inventorySize);
		if (stack.hasTagCompound() && stack.getTagCompound().hasKey("inventory")) {
			handler.deserializeNBT(stack.getTagCompound().getCompoundTag("inventory"));
		}
		return handler;
	}
}
