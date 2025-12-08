package com.windanesz.thieves;

import com.windanesz.thieves.capability.PlayerCapability;
import com.windanesz.thieves.command.CommandGetThiefGatherProgress;
import com.windanesz.thieves.command.CommandSetThiefGatherProgress;
import com.windanesz.thieves.init.ModBlocks;
import com.windanesz.thieves.init.ModLootTables;
import com.windanesz.thieves.network.PacketHandler;
import com.windanesz.thieves.thieves.Tags;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION)
public class Thieves implements ForgeChunkManager.LoadingCallback {

	/**
	 * title-cased modname
	 */
	public static final String MODNAME = "Thieves";
	public static final String MODID = Tags.MOD_ID;
	public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);
	public static Settings settings = new Settings();

	@Mod.Instance(Tags.MOD_ID)
	public static Thieves instance;

	@SidedProxy(clientSide = "com.windanesz.thieves.client.ClientProxy", serverSide = "com.windanesz.thieves.CommonProxy")
	public static CommonProxy proxy;

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		ForgeChunkManager.setForcedChunkLoadingCallback(instance, this);
		proxy.preInit(event);
		ModBlocks.registerTileEntities();
		ModLootTables.register();
		PlayerCapability.register();
	}


	@Mod.EventHandler
	public void init(FMLInitializationEvent event) {
		//	GameRegistry.registerWorldGenerator(new WorldgenThiefStash(), 0);
		proxy.registerColorHandlers();
		PacketHandler.initPackets();
	}

	@Mod.EventHandler
	public void serverStarting(FMLServerStartingEvent event) {
		event.registerServerCommand(new CommandSetThiefGatherProgress());
		event.registerServerCommand(new CommandGetThiefGatherProgress());
	}

	@Override
	public void ticketsLoaded(List<ForgeChunkManager.Ticket> tickets, World world) {
		for (ForgeChunkManager.Ticket ticket : tickets) {
			ForgeChunkManager.releaseTicket(ticket);
		}
	}
}