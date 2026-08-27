package com.windanesz.thieves.item;

import com.windanesz.thieves.Thieves;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

public class ItemStashMap extends Item {
    public ItemStashMap() {
        super();
        this.setRegistryName("stash_map");
        this.setTranslationKey(Thieves.MODID + ".stash_map");
        this.setMaxStackSize(1);
    }

    public static ItemStack createMap(BlockPos stashPos) {
        ItemStack stack = new ItemStack(com.windanesz.thieves.init.ModItems.STASH_MAP);
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setLong("StashPos", stashPos.toLong());
        stack.setTagCompound(nbt);
        return stack;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        ItemStack stack = playerIn.getHeldItem(handIn);
        
        if (!worldIn.isRemote) {
            if (stack.hasTagCompound() && stack.getTagCompound().hasKey("StashPos")) {
                BlockPos pos = BlockPos.fromLong(stack.getTagCompound().getLong("StashPos"));
                playerIn.sendMessage(new TextComponentString(TextFormatting.GOLD + "The map shows a stash hidden around: " + TextFormatting.YELLOW + "X: " + pos.getX() + ", Y: " + pos.getY() + ", Z: " + pos.getZ()));
            } else {
                playerIn.sendMessage(new TextComponentString(TextFormatting.RED + "This map seems to be blank..."));
            }
        }
        
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(TextFormatting.GRAY + "A hastily scrawled map pointing");
        tooltip.add(TextFormatting.GRAY + "to a hidden stash of stolen goods.");
        tooltip.add("");
        tooltip.add(TextFormatting.YELLOW + "Right-click to read coordinates.");
    }
}
