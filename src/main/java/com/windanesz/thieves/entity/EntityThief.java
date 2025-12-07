package com.windanesz.thieves.entity;

import com.google.common.base.Optional;
import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.entity.ai.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityOwnable;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.server.management.PreYggdrasilConverter;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.UUID;

public class EntityThief extends EntityMob implements IEntityOwnable {

	public static final ResourceLocation LOOT_TABLE = new ResourceLocation(Thieves.MODID, "entities/thief");
	protected static final DataParameter<Boolean> IS_STEALING = EntityDataManager.createKey(EntityThief.class, DataSerializers.BOOLEAN);
	protected static final DataParameter<Optional<UUID>> OWNER_UNIQUE_ID = EntityDataManager.<Optional<UUID>>createKey(EntityThief.class, DataSerializers.OPTIONAL_UNIQUE_ID);

	public EntityThief(World worldIn) {
		super(worldIn);
		this.setSize(0.6F, 1F);
		this.setCanPickUpLoot(true);
	}

	@Override
	protected void initEntityAI() {
		this.tasks.addTask(0, new ThiefAISteal(this));
		this.tasks.addTask(1, new ThiefAIRunBehindTarget(this, 2.0D));
		this.tasks.addTask(2, new EntityAIAttackMelee(this, 1.3D, false));
		this.tasks.addTask(2, new EntityAIAttackMelee(this, 1.3D, false));
		this.tasks.addTask(3, new ThiefAIFollowOwner(this, 1.3D, 5.0F, 3.0F));
		this.tasks.addTask(6, new EntityAIWatchClosest(this, EntityPlayer.class, 8.0F));
		this.tasks.addTask(7, new EntityAILookIdle(this));

		this.targetTasks.addTask(1, new ThiefAIOwnerHurtByTarget(this));
		this.targetTasks.addTask(2, new ThiefAIOwnerHurtTarget(this));
		this.targetTasks.addTask(3, new EntityAIHurtByTarget(this, true, new Class[0]));
		this.targetTasks.addTask(4, new EntityAINearestAttackableTarget<EntityPlayer>(this, EntityPlayer.class, true) {
			@Override
			public boolean shouldExecute() {
				// Always hostile when holding an idol, otherwise follow normal rules
				if (!EntityThief.this.dataManager.get(IS_STEALING)) {
					return super.shouldExecute();
				}
				return !EntityThief.this.hasOwner() && super.shouldExecute();
			}

			@Override
			protected boolean isSuitableTarget(EntityLivingBase target, boolean ignoreDisabled) {
				if (!super.isSuitableTarget(target, ignoreDisabled)) {
					return false;
				}
				if (target instanceof EntityPlayer) {
					if (((EntityPlayer) target).isCreative() || ((EntityPlayer) target).isSpectator()) {
						return false;
					}
				}
				return !EntityThief.this.isOwner(target);
			}
		});
	}

	@Override
	protected void applyEntityAttributes() {
		super.applyEntityAttributes();
		this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(20.0D);
		this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(32.0D);
		this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.2D);
		this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(3.0D);
	}

	@Override
	protected void entityInit() {
		super.entityInit();
		this.dataManager.register(IS_STEALING, false);
		this.dataManager.register(OWNER_UNIQUE_ID, Optional.absent());
	}

	public boolean isNeutral() {
		return this.dataManager.get(IS_STEALING);
	}

	public void setNeutral(boolean neutral) {
		this.dataManager.set(IS_STEALING, neutral);
	}

	public boolean isOwner(Entity entityIn) {
		return entityIn != null && entityIn.equals(this.getOwner());
	}

	@Override
	public void setAttackTarget(@Nullable EntityLivingBase entitylivingbaseIn) {
		super.setAttackTarget(entitylivingbaseIn);
	}


	@Override
	public boolean attackEntityFrom(DamageSource source, float amount) {
		if (this.isEntityInvulnerable(source)) {
			return false;
		}
		if (this.isOwner(source.getTrueSource())) {
			return false;
		}
		return super.attackEntityFrom(source, amount);
	}

	@Override
	public boolean attackEntityAsMob(Entity entityIn) {
		boolean flag = entityIn.attackEntityFrom(DamageSource.causeMobDamage(this), (float) this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getBaseValue());

		if (flag) {
			this.swingArm(EnumHand.MAIN_HAND);
		}
		return flag;
	}

	@Override
	public boolean processInteract(EntityPlayer player, EnumHand hand) {
		return super.processInteract(player, hand);
	}

	public void writeEntityToNBT(NBTTagCompound compound) {
		super.writeEntityToNBT(compound);

		if (this.getOwnerId() == null) {
			compound.setString("OwnerUUID", "");
		} else {
			compound.setString("OwnerUUID", this.getOwnerId().toString());
		}
	}

	public void readEntityFromNBT(NBTTagCompound compound) {
		super.readEntityFromNBT(compound);
		String s;

		if (compound.hasKey("OwnerUUID", 8)) {
			s = compound.getString("OwnerUUID");
		} else {
			String s1 = compound.getString("Owner");
			s = PreYggdrasilConverter.convertMobOwnerIfNeeded(this.getServer(), s1);
		}
		if (!s.isEmpty()) {
			this.setOwnerId(UUID.fromString(s));
		}
	}

	@Override
	public void onLivingUpdate() {
		super.onLivingUpdate();
	}

	public boolean hasOwner() {
		return this.getOwnerId() != null;
	}

	private void setTamedBy(EntityPlayer player) {
		this.setOwnerId(player.getUniqueID());
	}

	@Nullable
	@Override
	protected ResourceLocation getLootTable() {
		return LOOT_TABLE;
	}

	@Override
	public int getTalkInterval() {
		return 160;
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.VINDICATION_ILLAGER_AMBIENT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.VINDICATION_ILLAGER_DEATH;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource damageSourceIn) {
		return SoundEvents.ENTITY_VINDICATION_ILLAGER_HURT;
	}

	@Override
	public float getEyeHeight() {
		return super.getEyeHeight();
	}

	@Nullable
	public UUID getOwnerId() {
		return (UUID) ((Optional) this.dataManager.get(OWNER_UNIQUE_ID)).orNull();
	}

	public void setOwnerId(@Nullable UUID p_184754_1_) {
		this.dataManager.set(OWNER_UNIQUE_ID, Optional.fromNullable(p_184754_1_));
	}

	@Nullable
	public EntityLivingBase getOwner() {
		try {
			UUID uuid = this.getOwnerId();
			return uuid == null ? null : this.world.getPlayerEntityByUUID(uuid);
		} catch (IllegalArgumentException var2) {
			return null;
		}
	}

	public boolean shouldAttackEntity(EntityLivingBase target, EntityLivingBase owner) {
		return (!(target instanceof EntityCreeper));
	}

	@Override
	protected void setEquipmentBasedOnDifficulty(net.minecraft.world.DifficultyInstance difficulty) {
		super.setEquipmentBasedOnDifficulty(difficulty);

		if (this.rand.nextFloat() < 0.95F) {
			this.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
		}
	}

	@Override
	public boolean canPickUpLoot() {
		return true;
	}

	@Override
	protected void updateEquipmentIfNeeded(net.minecraft.entity.item.EntityItem itemEntity) {
		super.updateEquipmentIfNeeded(itemEntity);
	}
}
