package com.windanesz.thieves.client;

import com.windanesz.thieves.CommonProxy;
import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.capability.PlayerCapability;
import com.windanesz.thieves.client.renderer.RenderThief;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.packet.PacketPlayerSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ModelBakery;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(Side.CLIENT)
public class ClientProxy extends CommonProxy {

	@SubscribeEvent
	public static void registerItemModels(ModelRegistryEvent event) {

		for (Item item : Item.REGISTRY) {
			if (item.getRegistryName().getNamespace().equals(Thieves.MODID)) {
				registerItemModel(item); // Standard item model
			}
		}
	}

	/**
	 * Registers an item model, using the item's registry name as the model name (this convention makes it easier to
	 * keep track of everything). Variant defaults to "normal". Registers the model for all metadata values.
	 * Author: Electroblob
	 */
	private static void registerItemModel(Item item) {
		// Changing the last parameter from null to "inventory" fixed the item/block model weirdness. No idea why!
		ModelBakery.registerItemVariants(item, new ModelResourceLocation(item.getRegistryName(), "inventory"));
		// Assigns the model for all metadata values
		ModelLoader.setCustomMeshDefinition(item, s -> new ModelResourceLocation(item.getRegistryName(), "inventory"));
	}

	@Override
	public void preInit(FMLPreInitializationEvent event) {
		super.preInit(event);
		registerEntityRenderers();
		registerTileEntityRenderers();
	}

	@Override
	public void registerColorHandlers() {
//		Minecraft.getMinecraft().getBlockColors().registerBlockColorHandler((state, world, pos, tintIndex) -> {
//			// Only apply for tintindex 0
//			if (tintIndex == 0 && world != null && pos != null) {
//				return BiomeColorHelper.getGrassColorAtPos(world, pos);
//			}
//			return 0xFFFFFF;
//		}, );
	}

	private void registerEntityRenderers() {
		RenderingRegistry.registerEntityRenderingHandler(EntityThief.class, RenderThief::new);
		RenderingRegistry.registerEntityRenderingHandler(com.windanesz.thieves.entity.EntityThiefScout.class, RenderThief::new);
		RenderingRegistry.registerEntityRenderingHandler(com.windanesz.thieves.entity.EntityMasterThief.class, RenderThief::new);
	}

	private void registerTileEntityRenderers() {
	}

	@Override
	public void handlePlayerSyncPacket(PacketPlayerSync.Message message) {
		PlayerCapability data = PlayerCapability.get(Minecraft.getMinecraft().player);

		if (data != null) {
			data.robberyProgress = message.robberyProgress;
			data.completedRobberies = message.completedRobberies;
			data.scoutWarningActive = message.scoutWarningActive;
			data.scoutVisitCount = message.scoutVisitCount;
		}
	}
}
