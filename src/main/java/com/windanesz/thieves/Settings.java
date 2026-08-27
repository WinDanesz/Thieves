package com.windanesz.thieves;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Arrays;
import java.util.Locale;

@Config(modid = Thieves.MODID, name = Thieves.MODNAME)
public class Settings {

	@Config.Name("Robbery Settings")
	@Config.LangKey("settings." + Thieves.MODID + ":robbery_settings")
	public static RobberySettings robbery = new RobberySettings();
	@Config.Name("Base Detection Settings")
	@Config.LangKey("settings." + Thieves.MODID + ":base_detection_settings")
	public static BaseDetectionSettings baseDetection = new BaseDetectionSettings();
	@Config.Name("Item Value Settings")
	@Config.LangKey("settings." + Thieves.MODID + ":item_value_settings")
	public static ItemValueSettings itemValues = new ItemValueSettings();
	@Config.Name("Loot Bag Settings")
	@Config.LangKey("settings." + Thieves.MODID + ":loot_bag_settings")
	public static LootBagSettings lootBag = new LootBagSettings();
	@Config.Name("Inventory Detection Settings")
	@Config.LangKey("settings." + Thieves.MODID + ":inventory_detection_settings")
	public static InventoryDetectionSettings inventoryDetection = new InventoryDetectionSettings();

	public static ResourceLocation[] toResourceLocations(String... strings) {
		return Arrays.stream(strings).filter(s -> s != null && !s.trim().isEmpty()).map(s -> new ResourceLocation(s.toLowerCase(Locale.ROOT).trim())).toArray(ResourceLocation[]::new);
	}

	public static class RobberySettings {
		@Config.Comment("Distance from the player's base where thieves spawn at the start of a robbery (blocks)")
		@Config.RangeInt(min = 1, max = 32)
		public int thiefSpawnDistance = 4;
		
		@Config.Comment("Base progress buildup rate per minute")
		@Config.RangeDouble(min = 0.0, max = 10.0)
		public float baseProgressRate = 0.05F;

		@Config.Comment("Maximum progress buildup rate per minute (with high-value base)")
		@Config.RangeDouble(min = 0.0, max = 10.0)
		public float maxProgressRate = 0.5F;

		@Config.Comment("Progress threshold to trigger robbery")
		@Config.RangeDouble(min = 1.0, max = 1000.0)
		public float robberyThreshold = 100.0F;

		@Config.Comment("Minimum cooldown between robberies (days)")
		@Config.RangeInt(min = 1, max = 365)
		public int minCooldownDays = 7;

		@Config.Comment("Maximum cooldown between robberies (days)")
		@Config.RangeInt(min = 1, max = 365)
		public int maxCooldownDays = 14;

		@Config.Comment("Enable night-only robberies")
		public boolean nightOnly = true;

		@Config.Comment("Enable master thief variant")
		public boolean enableMasterThief = true;

		@Config.Comment("Master thief spawn chance (0.0-1.0)")
		@Config.RangeDouble(min = 0.0, max = 1.0)
		public float masterThiefChance = 0.1F;

		@Config.Comment("Robberies required before master thief can spawn")
		@Config.RangeInt(min = 0, max = 100)
		public int masterThiefMinRobberies = 5;

		@Config.Comment("Time in seconds for thieves to reach a chest before robbery fails")
		@Config.RangeInt(min = 10, max = 600)
		public int robberyTimeout = 60;

		@Config.Comment("Minimum distance from chest to place loot bag")
		@Config.RangeInt(min = 1, max = 16)
		public int lootBagSpawnMinDistance = 5;

		@Config.Comment("Maximum distance from chest to place loot bag")
		@Config.RangeInt(min = 1, max = 16)
		public int lootBagSpawnMaxDistance = 10;
	}

	public static class BaseDetectionSettings {
		@Config.Comment("Minimum chunk visits to consider for base")
		@Config.RangeInt(min = 1, max = 1000)
		public int minChunkVisits = 10;

		@Config.Comment("Minimum playtime in area (minutes)")
		@Config.RangeInt(min = 1, max = 10000)
		public int minTimeInArea = 30;

		@Config.Comment("Confidence threshold to confirm base (0.0-1.0)")
		@Config.RangeDouble(min = 0.0, max = 1.0)
		public float confidenceThreshold = 0.7F;

		@Config.Comment("Base detection update frequency (minutes)")
		@Config.RangeInt(min = 1, max = 1000)
		public int updateFrequencyMinutes = 10;

		@Config.Comment("Chunk scan radius around base candidate")
		@Config.RangeInt(min = 1, max = 32)
		public int scanRadius = 5;

		@Config.Comment("Minimum chests required to confirm base")
		@Config.RangeInt(min = 1, max = 100)
		public int minChestsForBase = 3;
	}

	public static class ItemValueSettings {
		@Config.Comment("Value score for diamond blocks")
		@Config.RangeDouble(min = 0.0, max = 1000.0)
		public float diamondBlock = 10.0F;

		@Config.Comment("Value score for diamond items")
		@Config.RangeDouble(min = 0.0, max = 1000.0)
		public float diamond = 5.0F;

		@Config.Comment("Value score for gold blocks")
		@Config.RangeDouble(min = 0.0, max = 1000.0)
		public float goldBlock = 5.0F;

		@Config.Comment("Value score for gold items")
		@Config.RangeDouble(min = 0.0, max = 1000.0)
		public float gold = 2.0F;

		@Config.Comment("Value score for iron blocks")
		@Config.RangeDouble(min = 0.0, max = 1000.0)
		public float ironBlock = 2.0F;

		@Config.Comment("Value score for iron items")
		@Config.RangeDouble(min = 0.0, max = 1000.0)
		public float iron = 1.0F;

		@Config.Comment("Value multiplier for enchanted items")
		@Config.RangeDouble(min = 1.0, max = 10.0)
		public float enchantedMultiplier = 2.0F;
	}

	public static class LootBagSettings {
		@Config.Comment("Loot bag inventory size")
		@Config.RangeInt(min = 9, max = 54)
		public int inventorySize = 27;

		@Config.Comment("Maximum total item count in loot bag (including stackable items)")
		@Config.RangeInt(min = 1, max = 10000)
		public int maxItemCount = 16;

		@Config.Comment("Loot bag durability (hits before breaking)")
		@Config.RangeInt(min = 1, max = 100)
		public int durability = 5;

		@Config.Comment("Should loot bag spill contents when broken")
		public boolean spillOnBreak = true;
	}

	public static class InventoryDetectionSettings {
		@Config.Comment("List of block registry names to ignore when thieves look for loot (e.g. 'minecraft:furnace').")
		public String[] inventoryBlacklist = new String[]{};

		@Config.Comment("If true, the blacklist acts as a whitelist instead. Only blocks on the list will be stolen from.")
		public boolean useWhitelistMode = false;
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