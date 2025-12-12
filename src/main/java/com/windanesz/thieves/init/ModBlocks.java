package com.windanesz.thieves.init;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.block.BlockLootBag;
import com.windanesz.thieves.block.TileEntityLootBag;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.registries.IForgeRegistry;

import javax.annotation.Nonnull;

@GameRegistry.ObjectHolder(Thieves.MODID)
@Mod.EventBusSubscriber
public class ModBlocks {

	public static final Block LOOT_BAG = placeholder();

	private ModBlocks() {
	}

	@Nonnull
	@SuppressWarnings("ConstantConditions")
	private static <T> T placeholder() {
		return null;
	}

	@SubscribeEvent
	public static void registerBlocks(RegistryEvent.Register<Block> event) {
		IForgeRegistry<Block> registry = event.getRegistry();
		
		// Register loot bag block
		registerBlock(registry, "loot_bag", new BlockLootBag(Material.CLOTH)
			.setBoundingBox(new net.minecraft.util.math.AxisAlignedBB(0.125D, 0.0D, 0.125D, 0.875D, 0.75D, 0.875D)));
	}

	public static void registerBlock(IForgeRegistry<Block> registry, String name, Block block) {
		block.setRegistryName(Thieves.MODID, name);
		block.setTranslationKey(block.getRegistryName().toString());
		registry.register(block);
	}

	public static void registerTileEntities() {
		// Nope, these still don't have their own registry...
		GameRegistry.registerTileEntity(TileEntityLootBag.class, new ResourceLocation(Thieves.MODID, "lost_loot"));
	}
}
