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
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
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
	public int hauntingProgress = 0;

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

	public int getHauntingProgress() {
		return hauntingProgress;
	}

	public void setHauntingProgress(int hauntingProgress) {
		int oldProgress = this.hauntingProgress;
		this.hauntingProgress = Math.max(0, Math.min(100, hauntingProgress));

		if (this.hauntingProgress > 0) {
			if (player instanceof EntityPlayerMP) {
				EntityPlayerMP playerMP = (EntityPlayerMP) player;
				Advancement advancement = playerMP.getServer().getAdvancementManager().getAdvancement(new ResourceLocation(Thieves.MODID, "haunted"));
				if (advancement != null) {
					if (!playerMP.getAdvancements().getProgress(advancement).isDone()) {
						playerMP.getAdvancements().grantCriterion(advancement, "haunted");
					}
				}
			}
		}

		if (oldProgress != this.hauntingProgress) {
			sync();
		}
	}

	public void addHauntingProgress(int amount) {
		setHauntingProgress(this.hauntingProgress + amount);
	}

	public void reduceHauntingProgress(int amount) {
		addHauntingProgress(-amount);
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
		this.hauntingProgress = data.hauntingProgress;
	}

	// ============================================== Event Handlers ==============================================

	/**
	 * Sends a packet to this player's client to synchronise necessary information. Only called server side.
	 */
	public void sync() {
		if (this.player instanceof EntityPlayerMP) {
			IMessage msg = new PacketPlayerSync.Message(this.hauntingProgress);
			PacketHandler.net.sendTo(msg, (EntityPlayerMP) this.player);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public NBTTagCompound serializeNBT() {

		NBTTagCompound properties = new NBTTagCompound();
		properties.setInteger("hauntingProgress", hauntingProgress);
		return properties;
	}

	@Override
	public void deserializeNBT(NBTTagCompound nbt) {

		if (nbt != null) {
			this.hauntingProgress = nbt.getInteger("hauntingProgress");
		}
	}

//	@SubscribeEvent
//	public static void onLivingUpdateEvent(TickEvent.PlayerTickEvent event) {
//
//		if (event.player.ticksExisted % 20 == 0 && !event.player.world.isRemote && event.player.world.getDifficulty() != EnumDifficulty.PEACEFUL) {
//			EntityPlayer player = event.player;
//			PlayerCapability cap = PlayerCapability.get(player);
//			if (PlayerCapability.get(player) != null && !player.capabilities.isCreativeMode) {
//				int hauntingProg = cap.hauntingProgress;
//				if (hauntingProg > 50) {
//					if (player.world.rand.nextInt(20) == 0) {
//						List<EntitySpecter> specters = player.world.getEntitiesWithinAABB(EntitySpecter.class, new AxisAlignedBB(player.getPosition()).grow(30));
//
//						if (specters.isEmpty()) {
//							BlockPos pos = Utils.findNearbyAirSpace(player.world, player.getPosition(), 6);
//							if (pos != null) {
//								EntitySpecter specter = new EntitySpecter(player.world);
//								specter.setPosition(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
//								player.world.spawnEntity(specter);
//								specter.setAttackTarget(player);
//							}
//						}
//					}
//				}
//			}
//		}
//	}

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
