package com.windanesz.thieves.capability;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.network.PacketHandler;
import com.windanesz.thieves.packet.PacketPlayerSync;
import net.minecraft.advancements.Advancement;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.Capability.IStorage;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

@Mod.EventBusSubscriber
public class PlayerCapability implements INBTSerializable<NBTTagCompound> {

	// This annotation does some crazy Forge magic behind the scenes and assigns this field a value.
	@CapabilityInject(PlayerCapability.class)
	private static final Capability<PlayerCapability> PLAYER_CAPABILITY = null;

	private final EntityPlayer player;

	// Robbery tracking
	public float robberyProgress = 0.0F;              // 0-100, triggers robbery at 100
	public int completedRobberies = 0;                // For difficulty scaling
	public long lastRobberyTime = 0L;                 // World time of last robbery

	// Base detection
	public BlockPos baseLocation = null;              // Detected base position
	public int baseDimension = 0;                     // Base dimension ID
	private boolean manuallySetBase = false;          // Whether base was manually set (vs auto-detected)
	private java.util.Map<Long, Integer> chunkVisits = new java.util.HashMap<>();      // ChunkPos hash -> visit count
	private java.util.Map<Long, Long> chunkVisitTimestamps = new java.util.HashMap<>(); // ChunkPos hash -> first visit time
	public int chestCountAtBase = 0;                  // Number of chests at base
	public float chestValueScore = 0.0F;              // Total value of items in base chests
	public long lastBaseDetectionTime = 0L;           // Last time base was recalculated

	public PlayerCapability() {
		this(null); // Nullary constructor for the registration method factory parameter
	}

	public PlayerCapability(EntityPlayer player) {
		this.player = player;
	}

	/**
	 * Called from preInit
	 */
	public static void register() {

		CapabilityManager.INSTANCE.register(PlayerCapability.class, new IStorage<PlayerCapability>() {
			// Unused but necessary...
			@Override
			public NBTBase writeNBT(Capability<PlayerCapability> capability, PlayerCapability instance, EnumFacing side) {
				return null;
			}

			@Override
			public void readNBT(Capability<PlayerCapability> capability, PlayerCapability instance, EnumFacing side, NBTBase nbt) {
			}

		}, PlayerCapability::new);
	}

	/**
	 * Returns the WizardData instance for the specified player.
	 */
	public static PlayerCapability get(EntityPlayer player) {
		return player.getCapability(PLAYER_CAPABILITY, null);
	}

	@SubscribeEvent
	// The type parameter here has to be Entity, not EntityPlayer, or the event won't get fired.
	public static void onCapabilityLoad(AttachCapabilitiesEvent<Entity> event) {

		if (event.getObject() instanceof EntityPlayer)
			event.addCapability(new ResourceLocation(Thieves.MODID, Thieves.MODNAME + "Data"), new PlayerCapability.Provider((EntityPlayer) event.getObject()));
	}

	@SubscribeEvent
	public static void onPlayerCloneEvent(PlayerEvent.Clone event) {

		PlayerCapability newData = PlayerCapability.get(event.getEntityPlayer());
		PlayerCapability oldData = PlayerCapability.get(event.getOriginal());

		newData.copyFrom(oldData, event.isWasDeath());

		newData.sync();
	}

	@SubscribeEvent
	public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
		if (!event.getEntity().world.isRemote && event.getEntity() instanceof EntityPlayerMP) {
			PlayerCapability data = PlayerCapability.get((EntityPlayer) event.getEntity());
			if (data != null) data.sync();
		}
	}

	@SubscribeEvent
	public static void onPlayerSleep(net.minecraftforge.event.entity.player.PlayerSleepInBedEvent event) {
		if (!event.getEntityPlayer().world.isRemote) {
			EntityPlayer player = event.getEntityPlayer();
			PlayerCapability cap = PlayerCapability.get(player);
			
			if (cap != null && !cap.isManuallySetBase()) {
				// Set base location to bed position
				BlockPos bedPos = event.getPos();
				cap.setManualBase(bedPos, player.dimension);
			}
		}
	}

	// ============================================== Robbery System Methods ==============================================

	/**
	 * Records a player visit to a chunk for base detection tracking.
	 */
	public void addChunkVisit(ChunkPos pos, long worldTime) {
		long hash = ChunkPos.asLong(pos.x, pos.z);
		chunkVisits.put(hash, chunkVisits.getOrDefault(hash, 0) + 1);
		chunkVisitTimestamps.putIfAbsent(hash, worldTime);
	}

	/**
	 * Gets the number of times the player has visited a specific chunk.
	 */
	public int getChunkVisitCount(ChunkPos pos) {
		long hash = ChunkPos.asLong(pos.x, pos.z);
		return chunkVisits.getOrDefault(hash, 0);
	}

	/**
	 * Gets the first visit timestamp for a chunk.
	 */
	public long getChunkFirstVisitTime(ChunkPos pos) {
		long hash = ChunkPos.asLong(pos.x, pos.z);
		return chunkVisitTimestamps.getOrDefault(hash, 0L);
	}

	/**
	 * Returns a copy of the chunk visit map for base detection analysis.
	 */
	public java.util.Map<Long, Integer> getChunkVisits() {
		return new java.util.HashMap<>(chunkVisits);
	}

	/**
	 * Increments robbery progress and syncs to client.
	 */
	public void incrementRobberyProgress(float amount) {
		float oldProgress = this.robberyProgress;
		this.robberyProgress = Math.min(100.0F, this.robberyProgress + amount);

		if (oldProgress != this.robberyProgress) {
			sync();
		}
	}

	/**
	 * Resets robbery progress after a robbery has occurred.
	 */
	public void resetRobberyProgress() {
		this.robberyProgress = 0.0F;
		sync();
	}

	/**
	 * Manually sets the player's base location.
	 */
	public void setManualBase(BlockPos pos, int dimension) {
		this.baseLocation = pos;
		this.baseDimension = dimension;
		this.manuallySetBase = true;
		this.lastBaseDetectionTime = System.currentTimeMillis();
		sync();
	}

	/**
	 * Clears the manually set base and re-enables auto-detection.
	 */
	public void clearManualBase() {
		this.manuallySetBase = false;
		this.baseLocation = null;
		this.baseDimension = 0;
		this.chestCountAtBase = 0;
		this.chestValueScore = 0.0F;
		sync();
	}

	/**
	 * Returns whether the base was manually set.
	 */
	public boolean isManuallySetBase() {
		return this.manuallySetBase;
	}

	/**
	 * Checks if all conditions are met to trigger a robbery.
	 */
	public boolean canTriggerRobbery(World world) {
		// Must have full progress
		if (robberyProgress < 100.0F) return false;

		// Must have a detected base
		if (baseLocation == null) return false;

		// Check cooldown period
		long timeSinceLastRobbery = world.getTotalWorldTime() - lastRobberyTime;
		long cooldownTicks = (long) (com.windanesz.thieves.Settings.robbery.minCooldownDays * 24000);
		if (timeSinceLastRobbery < cooldownTicks) return false;

		// Night-only check (if enabled)
		if (com.windanesz.thieves.Settings.robbery.nightOnly) {
			long dayTime = world.getWorldTime() % 24000;
			if (dayTime < 12542 || dayTime > 23458) return false;
		}

		return true;
	}

	/**
	 * Marks that a robbery has been completed.
	 */
	public void completeRobbery(World world) {
		completedRobberies++;
		lastRobberyTime = world.getTotalWorldTime();
		resetRobberyProgress();
	}

	/**
	 * Called from the event handler each time the associated player entity is cloned, i.e. on respawn or when
	 * travelling to a different dimension. Used to copy over any data that should persist over player death. This
	 * is the inverse of the old onPlayerDeath method, which reset the data that shouldn't persist.
	 *
	 * @param data    The old WizardData whose data is to be copied over.
	 * @param respawn True if the player died and is respawning, false if they are just travelling between dimensions.
	 */
	public void copyFrom(PlayerCapability data, boolean respawn) {

		// Copy robbery system data
		this.robberyProgress = data.robberyProgress;
		this.completedRobberies = data.completedRobberies;
		this.lastRobberyTime = data.lastRobberyTime;

		// Copy base detection data
		this.baseLocation = data.baseLocation;
		this.baseDimension = data.baseDimension;
		this.chunkVisits = new java.util.HashMap<>(data.chunkVisits);
		this.chunkVisitTimestamps = new java.util.HashMap<>(data.chunkVisitTimestamps);
		this.chestCountAtBase = data.chestCountAtBase;
		this.chestValueScore = data.chestValueScore;
		this.lastBaseDetectionTime = data.lastBaseDetectionTime;
	}

	// ============================================== Event Handlers ==============================================

	/**
	 * Sends a packet to this player's client to synchronise necessary information. Only called server side.
	 */
	public void sync() {
		if (this.player instanceof EntityPlayerMP) {
			IMessage msg = new PacketPlayerSync.Message(this.robberyProgress,
				this.completedRobberies);
			PacketHandler.net.sendTo(msg, (EntityPlayerMP) this.player);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public NBTTagCompound serializeNBT() {

		NBTTagCompound properties = new NBTTagCompound();
		// Robbery tracking
		properties.setFloat("robberyProgress", robberyProgress);
		properties.setInteger("completedRobberies", completedRobberies);
		properties.setLong("lastRobberyTime", lastRobberyTime);

		// Base detection
		if (baseLocation != null) {
			properties.setLong("baseLocation", baseLocation.toLong());
		}
		properties.setInteger("baseDimension", baseDimension);
		properties.setBoolean("manuallySetBase", manuallySetBase);
		properties.setInteger("chestCountAtBase", chestCountAtBase);
		properties.setFloat("chestValueScore", chestValueScore);
		properties.setLong("lastBaseDetectionTime", lastBaseDetectionTime);

		// Serialize chunk visits
		NBTTagList chunkVisitsList = new NBTTagList();
		for (java.util.Map.Entry<Long, Integer> entry : chunkVisits.entrySet()) {
			NBTTagCompound chunkData = new NBTTagCompound();
			chunkData.setLong("pos", entry.getKey());
			chunkData.setInteger("count", entry.getValue());
			chunkVisitsList.appendTag(chunkData);
		}
		properties.setTag("chunkVisits", chunkVisitsList);

		// Serialize chunk visit timestamps
		NBTTagList timestampsList = new NBTTagList();
		for (java.util.Map.Entry<Long, Long> entry : chunkVisitTimestamps.entrySet()) {
			NBTTagCompound timestampData = new NBTTagCompound();
			timestampData.setLong("pos", entry.getKey());
			timestampData.setLong("time", entry.getValue());
			timestampsList.appendTag(timestampData);
		}
		properties.setTag("chunkVisitTimestamps", timestampsList);

		return properties;
	}

	@Override
	public void deserializeNBT(NBTTagCompound nbt) {

		if (nbt != null) {

			// Robbery tracking
			this.robberyProgress = nbt.getFloat("robberyProgress");
			this.completedRobberies = nbt.getInteger("completedRobberies");
			this.lastRobberyTime = nbt.getLong("lastRobberyTime");

			// Base detection
			if (nbt.hasKey("baseLocation")) {
				this.baseLocation = BlockPos.fromLong(nbt.getLong("baseLocation"));
			}
			this.baseDimension = nbt.getInteger("baseDimension");
			this.manuallySetBase = nbt.getBoolean("manuallySetBase");
			this.chestCountAtBase = nbt.getInteger("chestCountAtBase");
			this.chestValueScore = nbt.getFloat("chestValueScore");
			this.lastBaseDetectionTime = nbt.getLong("lastBaseDetectionTime");

			// Deserialize chunk visits
			this.chunkVisits.clear();
			NBTTagList chunkVisitsList = nbt.getTagList("chunkVisits", 10); // 10 = compound tag type
			for (int i = 0; i < chunkVisitsList.tagCount(); i++) {
				NBTTagCompound chunkData = chunkVisitsList.getCompoundTagAt(i);
				this.chunkVisits.put(chunkData.getLong("pos"), chunkData.getInteger("count"));
			}

			// Deserialize chunk visit timestamps
			this.chunkVisitTimestamps.clear();
			NBTTagList timestampsList = nbt.getTagList("chunkVisitTimestamps", 10);
			for (int i = 0; i < timestampsList.tagCount(); i++) {
				NBTTagCompound timestampData = timestampsList.getCompoundTagAt(i);
				this.chunkVisitTimestamps.put(timestampData.getLong("pos"), timestampData.getLong("time"));
			}
		}
	}

	public static class Provider implements ICapabilitySerializable<NBTTagCompound> {

		private final PlayerCapability data;

		public Provider(EntityPlayer player) {
			data = new PlayerCapability(player);
		}

		@Override
		public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
			return capability == PLAYER_CAPABILITY;
		}

		@Override
		public <T> T getCapability(Capability<T> capability, EnumFacing facing) {

			if (capability == PLAYER_CAPABILITY) {
				return PLAYER_CAPABILITY.cast(data);
			}

			return null;
		}

		@Override
		public NBTTagCompound serializeNBT() {
			return data.serializeNBT();
		}

		@Override
		public void deserializeNBT(NBTTagCompound nbt) {
			data.deserializeNBT(nbt);
		}

	}

}
