package com.windanesz.thieves.init;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.item.ItemLootBag;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.registries.IForgeRegistry;

import javax.annotation.Nonnull;

@GameRegistry.ObjectHolder(Thieves.MODID)
@Mod.EventBusSubscriber
public class ModItems {

	public static final Item LOOT_BAG_ITEM = placeholder();

	private ModItems() {
	}

	@Nonnull
	@SuppressWarnings("ConstantConditions")
	private static <T> T placeholder() {
		return null;
	}

	// New method for ItemBlock registration
	@SubscribeEvent
	public static void registerItems(RegistryEvent.Register<Item> event) {
		IForgeRegistry<Item> registry = event.getRegistry();
		
		// Register ItemBlocks for blocks
		registerItemBlock(registry, ModBlocks.LOOT_BAG);
		
		// Register items
		//^^registry.register(new ItemLootBag());
	}

	// Helper for registering ItemBlocks
	private static void registerItemBlock(IForgeRegistry<Item> registry, Block block) {
		ItemBlock itemBlock = new ItemBlock(block);
		itemBlock.setRegistryName(block.getRegistryName());

		registry.register(itemBlock);
	}

	public static void registerItem(IForgeRegistry<Item> registry, String name, Item item) {
		registerItem(registry, name, item, false);
	}

	public static void registerItem(IForgeRegistry<Item> registry, String name, Item item, boolean setTabIcon) {
		item.setRegistryName(Thieves.MODID, name);
		item.setTranslationKey(item.getRegistryName().toString());
		registry.register(item);
	}

}
