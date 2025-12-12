package com.windanesz.thieves.entity;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.entity.ai.ThiefScoutAIFlee;
import com.windanesz.thieves.entity.ai.ThiefScoutAIObserve;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAIWander;
import net.minecraft.entity.ai.EntityAIWatchClosest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Scout thief variant that warns players before robberies.
 * Observes player from a distance, flees if approached, despawns after timer.
 */
public class EntityThiefScout extends EntityThief {

	public static final ResourceLocation LOOT_TABLE = new ResourceLocation(Thieves.MODID, "entities/thief_scout");
	
	private int observationTimer = 0;
	private int maxObservationTime = 6000; // 5 minutes default
	private boolean hasBeenSpotted = false;

	public EntityThiefScout(World worldIn) {
		super(worldIn);
		this.maxObservationTime = 6000 + worldIn.rand.nextInt(6000); // 5-10 minutes
	}

	@Override
	protected void initEntityAI() {
		// Clear parent AI tasks
		this.tasks.taskEntries.clear();
		this.targetTasks.taskEntries.clear();

		// Scout-specific AI
		this.tasks.addTask(0, new ThiefScoutAIFlee(this, 1.4D, 8.0D)); // Flee when player within 8 blocks
		this.tasks.addTask(1, new ThiefScoutAIObserve(this, EntityPlayer.class, 24.0F)); // Observe from distance
		this.tasks.addTask(2, new EntityAIWander(this, 0.8D)); // Wander slowly
		this.tasks.addTask(3, new EntityAIWatchClosest(this, EntityPlayer.class, 32.0F)); // Watch from far
		this.tasks.addTask(4, new EntityAILookIdle(this));

		// No attack behaviors for scouts
	}

	@Override
	protected void applyEntityAttributes() {
		super.applyEntityAttributes();
		this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(15.0D);
		this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.25D); // Faster than regular
		this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(48.0D); // Longer sight range
		// Remove attack damage since scouts don't attack
	}

	@Override
	public void onLivingUpdate() {
		super.onLivingUpdate();

		if (!this.world.isRemote) {
			observationTimer++;

			// Check if observed long enough
			if (observationTimer >= maxObservationTime) {
				// Despawn peacefully
				this.setDead();
			}

			// Check if player is looking at scout
			if (!hasBeenSpotted) {
				EntityPlayer nearestPlayer = this.world.getClosestPlayerToEntity(this, 32.0D);
				if (nearestPlayer != null && this.canEntityBeSeen(nearestPlayer)) {
					// Check if player is looking at scout
					if (isPlayerLookingAt(nearestPlayer)) {
						hasBeenSpotted = true;
						onSpottedByPlayer(nearestPlayer);
					}
				}
			}

			// Spawn subtle particles when observing
			if (observationTimer % 40 == 0) { // Every 2 seconds
				spawnObservationParticles();
			}
		}
	}

	/**
	 * Checks if player is looking directly at this scout.
	 */
	private boolean isPlayerLookingAt(EntityPlayer player) {
		// Get player's look vector
		net.minecraft.util.math.Vec3d look = player.getLookVec();
		// Get vector from player to scout
		net.minecraft.util.math.Vec3d toScout = new net.minecraft.util.math.Vec3d(
			this.posX - player.posX,
			this.posY + this.getEyeHeight() - (player.posY + player.getEyeHeight()),
			this.posZ - player.posZ
		).normalize();

		// Check if vectors are similar (dot product close to 1)
		double dot = look.dotProduct(toScout);
		return dot > 0.95D; // Looking almost directly at scout
	}

	/**
	 * Called when player spots the scout for the first time.
	 */
	private void onSpottedByPlayer(EntityPlayer player) {
		// Send warning message
		player.sendMessage(new net.minecraft.util.text.TextComponentTranslation("message.thieves.scout_spotted")
			.setStyle(new net.minecraft.util.text.Style().setColor(net.minecraft.util.text.TextFormatting.DARK_RED)));
		
		// Grant advancement
		if (player instanceof net.minecraft.entity.player.EntityPlayerMP) {
			net.minecraft.entity.player.EntityPlayerMP playerMP = (net.minecraft.entity.player.EntityPlayerMP) player;
			net.minecraft.advancements.Advancement advancement = playerMP.getServer()
				.getAdvancementManager()
				.getAdvancement(new ResourceLocation(Thieves.MODID, "being_watched"));
			
			if (advancement != null) {
				if (!playerMP.getAdvancements().getProgress(advancement).isDone()) {
					playerMP.getAdvancements().grantCriterion(advancement, "spotted_scout");
				}
			}
		}

		// Play subtle sound
		this.playSound(net.minecraft.init.SoundEvents.ENTITY_ENDERMEN_STARE, 0.3F, 1.2F);
	}

	/**
	 * Spawns subtle particle effects while observing.
	 */
	private void spawnObservationParticles() {
		if (this.world.isRemote) {
			return;
		}

		// Only spawn particles at night or in dark areas
		if (this.world.getLight(this.getPosition()) < 8) {
			// Subtle eye glow particles
			((net.minecraft.world.WorldServer) this.world).spawnParticle(
				net.minecraft.util.EnumParticleTypes.VILLAGER_ANGRY,
				this.posX,
				this.posY + this.getEyeHeight(),
				this.posZ,
				1, // particle count
				0.1D, 0.0D, 0.1D, // spread
				0.0D // speed
			);
		}
	}

	@Override
	public boolean attackEntityFrom(net.minecraft.util.DamageSource source, float amount) {
		// If attacked by player, mark as spotted
		if (source.getTrueSource() instanceof EntityPlayer) {
			hasBeenSpotted = true;
			onAttackedByPlayer((EntityPlayer) source.getTrueSource());
		}
		return super.attackEntityFrom(source, amount);
	}

	/**
	 * Called when scout is attacked by a player.
	 */
	private void onAttackedByPlayer(EntityPlayer player) {
		// Delay robbery by setting a flag in player capability
		com.windanesz.thieves.capability.PlayerCapability cap = 
			com.windanesz.thieves.capability.PlayerCapability.get(player);
		
		if (cap != null) {
			// Delay next robbery attempt
			cap.lastScoutSpawnTime = this.world.getTotalWorldTime();
			cap.clearScoutWarning();
			
			// Reduce robbery progress slightly as punishment
			cap.robberyProgress = Math.max(0, cap.robberyProgress - 10.0F);
			cap.sync();
		}
	}

	@Override
	protected void setEquipmentBasedOnDifficulty(net.minecraft.world.DifficultyInstance difficulty) {
		// Scouts don't carry weapons
	}

	@Override
	public boolean canPickUpLoot() {
		return false; // Scouts don't pick up items
	}

	@Override
	protected ResourceLocation getLootTable() {
		return LOOT_TABLE;
	}

	@Override
	public void readEntityFromNBT(net.minecraft.nbt.NBTTagCompound compound) {
		super.readEntityFromNBT(compound);
		this.observationTimer = compound.getInteger("observationTimer");
		this.maxObservationTime = compound.getInteger("maxObservationTime");
		this.hasBeenSpotted = compound.getBoolean("hasBeenSpotted");
	}

	@Override
	public void writeEntityToNBT(net.minecraft.nbt.NBTTagCompound compound) {
		super.writeEntityToNBT(compound);
		compound.setInteger("observationTimer", this.observationTimer);
		compound.setInteger("maxObservationTime", this.maxObservationTime);
		compound.setBoolean("hasBeenSpotted", this.hasBeenSpotted);
	}
}
