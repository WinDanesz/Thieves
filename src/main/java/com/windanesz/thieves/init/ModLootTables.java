package com.windanesz.thieves.init;

import com.windanesz.thieves.Thieves;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.storage.loot.LootTableList;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class ModLootTables {

	private ModLootTables() {
	}

	/**
	 * Called from the preInit method in the main mod class to register the custom dungeon loot.
	 */
	public static void register() {
		LootTableList.register(new ResourceLocation(Thieves.MODID, "entities/thief"));
	}
}