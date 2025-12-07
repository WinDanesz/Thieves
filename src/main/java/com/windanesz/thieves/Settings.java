package com.windanesz.thieves;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Config(modid = Thieves.MODID, name = Thieves.MODNAME)
public class Settings {

	@Config.Name("Worldgen Settings")
	@Config.LangKey("settings." + Thieves.MODID + ":general_settings")
	public static WorldgenSettings worldgenSettings = new WorldgenSettings();
	@Config.Name("Misc Settings")
	@Config.LangKey("settings." + Thieves.MODID + ":general_settings")
	public static MiscSettings miscSettings = new MiscSettings();
	@Config.Name("Client Settings")
	@Config.LangKey("settings." + Thieves.MODID + ":client_settings")
	public static ClientSettings clientSettings = new ClientSettings();
	// These are set after config load, not part of config fields
	public List<ResourceLocation> BiomeWhitelist = Arrays.asList(toResourceLocations(worldgenSettings.BiomeWhitelist));
	public List<ResourceLocation> BiomeBlacklist = Arrays.asList(toResourceLocations(worldgenSettings.BiomeBlacklist));

	public static ResourceLocation[] toResourceLocations(String... strings) {
		return Arrays.stream(strings).filter(s -> s != null && !s.trim().isEmpty()).map(s -> new ResourceLocation(s.toLowerCase(Locale.ROOT).trim())).toArray(ResourceLocation[]::new);
	}

	public static class WorldgenSettings {

		@Config.Name("Dimensions")
		@Config.Comment("")
		@Config.RequiresMcRestart
		public int[] dimensionList = {0};

		@Config.Name("Frequency")
		@Config.Comment("(default: 1)")
		public int frequency = 1;

		@Config.Name("Biome Whitelist")
		@Config.Comment("")
		public String[] BiomeWhitelist = new String[0];

		@Config.Name("Biome Blacklist")
		@Config.Comment("")
		public String[] BiomeBlacklist = new String[0];
	}

	public static class MiscSettings {

	}

	public static class ClientSettings {
	}

	@SuppressWarnings("unused")
	@Mod.EventBusSubscriber(modid = Thieves.MODID)
	private static class EventHandler {
		@SubscribeEvent
		public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
			if (event.getModID().equals(Thieves.MODID)) {
				ConfigManager.sync(Thieves.MODID, Config.Type.INSTANCE);
			}
		}
	}
}