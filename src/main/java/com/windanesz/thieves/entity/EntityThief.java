package com.windanesz.thieves.entity;

import com.google.common.base.Optional;
import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.block.TileEntityLootBag;
import com.windanesz.thieves.entity.ai.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.entity.IEntityOwnable;
import net.minecraft.entity.IRangedAttackMob;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.server.management.PreYggdrasilConverter;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.UUID;

public class EntityThief extends EntityMob implements IEntityOwnable, IRangedAttackMob {

    // Robbery loot bag position for AI targeting
    private BlockPos robberyLootBagPos = null;
    private UUID robberyTargetUUID = null;

    /**
     * How many ticks this robbery thief has existed without being claimed by
     * an active {@link com.windanesz.thieves.robbery.ActiveRobbery} entry.
     * Reloaded thieves start unclaimed; RobberyManager claims them when they
     * match a tracked robbery. If unclaimed for too long, the thief self-despawns
     * to prevent permanently wandering orphan thieves after a mid-robbery reload.
     */
    private int orphanTicksUnclaimed = 0;

    /**
     * Ticks before an unclaimed robbery thief despawns.
     *
     * <p>10 minutes (12 000 ticks) gives players time to log back into a
     * server after a restart and have {@link RobberyManager#onEntityJoinWorld}
     * claim the reloaded thief before the orphan timer fires.  True orphans
     * (e.g. from a crash before the cooldown stamp was written) will still
     * be cleaned up eventually.</p>
     */
    private static final int ORPHAN_DESPAWN_TICKS = 12000;

    /** Set to true by RobberyManager once this thief is assigned to a live robbery. */
    public boolean claimedByRobbery = false;

    public void setRobberyLootBagPos(BlockPos pos) {
        this.robberyLootBagPos = pos;
    }

    public BlockPos getRobberyLootBagPos() {
        return this.robberyLootBagPos;
    }

    public void setRobberyTargetUUID(UUID uuid) {
        this.robberyTargetUUID = uuid;
    }

    public UUID getRobberyTargetUUID() {
        return this.robberyTargetUUID;
    }

    // Skin variation support
    protected static final DataParameter<Integer> SKIN_INDEX = EntityDataManager.createKey(EntityThief.class, DataSerializers.VARINT);
    public static final int SKIN_VARIATION_COUNT = 6;

    public static final ResourceLocation LOOT_TABLE = new ResourceLocation(Thieves.MODID, "entities/thief");
    protected static final DataParameter<Boolean> IS_STEALING = EntityDataManager.createKey(EntityThief.class, DataSerializers.BOOLEAN);
    protected static final DataParameter<Boolean> IS_ESCAPING = EntityDataManager.createKey(EntityThief.class, DataSerializers.BOOLEAN);
    protected static final DataParameter<Boolean> IS_ROBBERY_THIEF = EntityDataManager.createKey(EntityThief.class, DataSerializers.BOOLEAN);
    protected static final DataParameter<Optional<UUID>> OWNER_UNIQUE_ID = EntityDataManager.<Optional<UUID>>createKey(EntityThief.class, DataSerializers.OPTIONAL_UNIQUE_ID);

    public EntityThief(World worldIn) {
        super(worldIn);
        this.setSize(0.5F, 1.8F);
        this.setCanPickUpLoot(true);
        // Enable door interaction for pathfinding
        net.minecraft.pathfinding.PathNavigateGround navigator = (net.minecraft.pathfinding.PathNavigateGround) this.getNavigator();
        navigator.setBreakDoors(true);
        navigator.setEnterDoors(true);
        navigator.setCanSwim(true);
    }

    @Override
    protected void initEntityAI() {
        this.tasks.addTask(0, new EntityAISwimming(this));
        // Combat AI is applied dynamically via applyCombatAI() when a weapon is equipped.
        // Default to melee so thieves without weapons still defend themselves.
        this.tasks.addTask(2, new EntityAIAttackMelee(this, 1.3D, false));
        this.tasks.addTask(3, new ThiefAIEscapeWithLoot(this));
        this.tasks.addTask(4, new ThiefAIPickupLootBag(this));
        this.tasks.addTask(5, new ThiefAIStealToLootBag(this));
        this.tasks.addTask(6, new ThiefAIEscortToHideout(this));
        this.tasks.addTask(7, new EntityAIOpenDoor(this, true));
        this.tasks.addTask(8, new ThiefAIRunBehindTarget(this, 2.0D));
        this.tasks.addTask(9, new EntityAIWanderAvoidWater(this, 1.0D));
        this.tasks.addTask(10, new ThiefAIFollowOwner(this, 1.3D, 5.0F, 3.0F));
        this.tasks.addTask(11, new EntityAIWatchClosest(this, EntityPlayer.class, 8.0F));
        this.tasks.addTask(12, new EntityAILookIdle(this));

        this.targetTasks.addTask(1, new ThiefAIOwnerHurtByTarget(this));
        this.targetTasks.addTask(2, new ThiefAIOwnerHurtTarget(this));
        this.targetTasks.addTask(3, new EntityAIHurtByTarget(this, true, new Class[0]) {
            @Override
            public boolean shouldExecute() {
                // Don't target enemies while escaping
                if (EntityThief.this.isEscaping()) {
                    return false;
                }
                return super.shouldExecute();
            }
        });
        this.targetTasks.addTask(4, new EntityAINearestAttackableTarget<EntityPlayer>(this, EntityPlayer.class, true) {
            @Override
            public boolean shouldExecute() {
                // Don't target players while escaping
                if (EntityThief.this.isEscaping()) {
                    return false;
                }
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

    /**
     * Swaps combat AI to match the equipped weapon so ranged and melee tasks
     * never conflict. Call this after changing the thief's weapon.
     *
     * @param useBow {@code true} to switch to bow/ranged AI, {@code false} for melee.
     */
    public void applyCombatAI(boolean useBow) {
        // Remove any existing combat tasks at priorities 1 and 2
        this.tasks.taskEntries.removeIf(entry ->
                entry.action instanceof EntityAIAttackRangedBow ||
                entry.action instanceof EntityAIAttackMelee);

        if (useBow) {
            this.tasks.addTask(1, new EntityAIAttackRangedBow(this, 1.0D, 20, 15.0F));
        } else {
            this.tasks.addTask(2, new EntityAIAttackMelee(this, 1.3D, false));
        }
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
        this.dataManager.register(IS_ESCAPING, false);
        this.dataManager.register(IS_ROBBERY_THIEF, false);
        this.dataManager.register(OWNER_UNIQUE_ID, Optional.absent());
        this.dataManager.register(SKIN_INDEX, -1);
    }

    public boolean isNeutral() {
        return this.dataManager.get(IS_STEALING);
    }

    public void setNeutral(boolean neutral) {
        this.dataManager.set(IS_STEALING, neutral);
    }

    public boolean isEscaping() {
        return this.dataManager.get(IS_ESCAPING);
    }

    public void setEscaping(boolean escaping) {
        this.dataManager.set(IS_ESCAPING, escaping);
    }

    public boolean isRobberyThief() {
        return this.dataManager.get(IS_ROBBERY_THIEF);
    }

    public void setRobberyThief(boolean isRobberyThief) {
        this.dataManager.set(IS_ROBBERY_THIEF, isRobberyThief);
    }

    public boolean isOwner(Entity entityIn) {
        return entityIn != null && entityIn.equals(this.getOwner());
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
        float damage = (float) this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getBaseValue();

        // Add weapon damage from held item's attribute modifiers
        ItemStack heldItem = this.getHeldItemMainhand();
        if (!heldItem.isEmpty()) {
            com.google.common.collect.Multimap<String, net.minecraft.entity.ai.attributes.AttributeModifier> modifiers =
                    heldItem.getAttributeModifiers(EntityEquipmentSlot.MAINHAND);

            if (modifiers.containsKey(SharedMonsterAttributes.ATTACK_DAMAGE.getName())) {
                for (net.minecraft.entity.ai.attributes.AttributeModifier modifier :
                        modifiers.get(SharedMonsterAttributes.ATTACK_DAMAGE.getName())) {
                    damage += modifier.getAmount();
                }
            }

            // Add enchantment damage bonuses (Sharpness, Smite, Bane of Arthropods, etc.)
            if (entityIn instanceof EntityLivingBase) {
                damage += net.minecraft.enchantment.EnchantmentHelper.getModifierForCreature(heldItem,
                        ((EntityLivingBase) entityIn).getCreatureAttribute());
            }
        }

        boolean flag = entityIn.attackEntityFrom(DamageSource.causeMobDamage(this), damage);

        if (flag) {
            this.applyEnchantments(this, entityIn);
            this.swingArm(EnumHand.MAIN_HAND);
        }
        return flag;
    }

    @Override
    public void attackEntityWithRangedAttack(EntityLivingBase target, float distanceFactor) {
        EntityArrow arrow = new EntityTippedArrow(this.world, this);
        double d0 = target.posX - this.posX;
        double d1 = target.getEntityBoundingBox().minY + (double) (target.height / 3.0F) - arrow.posY;
        double d2 = target.posZ - this.posZ;
        double d3 = (double) MathHelper.sqrt(d0 * d0 + d2 * d2);
        arrow.shoot(d0, d1 + d3 * 0.20000000298023224D, d2, 1.6F, (float) (14 - this.world.getDifficulty().getId() * 4));

        // Add power enchantment bonus damage
        ItemStack bow = this.getHeldItemMainhand();
        if (bow.getItem() == Items.BOW) {
            int powerLevel = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(
                    net.minecraft.init.Enchantments.POWER, bow);
            if (powerLevel > 0) {
                arrow.setDamage(arrow.getDamage() + (double) powerLevel * 0.5D + 0.5D);
            }

            int punchLevel = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(
                    net.minecraft.init.Enchantments.PUNCH, bow);
            if (punchLevel > 0) {
                arrow.setKnockbackStrength(punchLevel);
            }

            if (net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(
                    net.minecraft.init.Enchantments.FLAME, bow) > 0) {
                arrow.setFire(100);
            }
        }

        this.playSound(SoundEvents.ENTITY_SKELETON_SHOOT, 1.0F, 1.0F / (this.getRNG().nextFloat() * 0.4F + 0.8F));
        this.world.spawnEntity(arrow);
    }

    @Override
    public void setSwingingArms(boolean swingingArms) {
        // Required by IRangedAttackMob
    }

    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        if (this.getOwnerId() == null) {
            compound.setString("OwnerUUID", "");
        } else {
            compound.setString("OwnerUUID", this.getOwnerId().toString());
        }
        compound.setBoolean("IsRobberyThief", this.isRobberyThief());
        compound.setInteger("SkinIndex", this.getSkinIndex());
        // Persist loot bag position so thieves resuming after a world reload can
        // continue escaping to the correct position (Fix: world close persistence)
        if (this.robberyLootBagPos != null) {
            compound.setLong("RobberyLootBagPos", this.robberyLootBagPos.toLong());
        }
        if (this.robberyTargetUUID != null) {
            compound.setString("RobberyTargetUUID", this.robberyTargetUUID.toString());
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
        if (compound.hasKey("IsRobberyThief")) {
            this.setRobberyThief(compound.getBoolean("IsRobberyThief"));
        }
        if (compound.hasKey("SkinIndex")) {
            this.setSkinIndex(compound.getInteger("SkinIndex"));
        }
        // Restore loot bag position if the thief was mid-escape when the world closed
        if (compound.hasKey("RobberyLootBagPos")) {
            this.robberyLootBagPos = BlockPos.fromLong(compound.getLong("RobberyLootBagPos"));
        }
        if (compound.hasKey("RobberyTargetUUID")) {
            this.robberyTargetUUID = UUID.fromString(compound.getString("RobberyTargetUUID"));
        }
    }

    @Override
    public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, @Nullable IEntityLivingData livingdata) {
        livingdata = super.onInitialSpawn(difficulty, livingdata);
        // If skin index is not set, randomize it (for entities spawned without NBT)
        if (this.getSkinIndex() < 0) {
            this.setSkinIndex(this.rand.nextInt(SKIN_VARIATION_COUNT));
        }
        // Freshly spawned (not reloaded) robbery thieves are immediately claimed
        // by RobberySpawner before this is ever called at start, so flag defaults false
        // which is correct for normal wild spawns. Robbery thieves set this via
        // RobberyManager on EntityJoinWorldEvent.
        return livingdata;
    }

    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();

        // Orphan check: if this is a robbery thief but no active robbery has
        // claimed it (e.g., world was closed mid-robbery and reloaded), count
        // up and despawn once the grace window expires. Only runs server-side.
        if (!world.isRemote && isRobberyThief() && !claimedByRobbery) {
            orphanTicksUnclaimed++;
            if (orphanTicksUnclaimed >= ORPHAN_DESPAWN_TICKS) {
                Thieves.LOGGER.info("Despawning orphaned robbery thief at {} (no active robbery tracked it after reload).",
                        this.getPosition());
                this.setDead();
            }
        }
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

    /**
     * Returns the skin index for this thief.
     */
    public int getSkinIndex() {
        return this.dataManager.get(SKIN_INDEX);
    }

    /**
     * Sets the skin index for this thief.
     */
    public void setSkinIndex(int index) {
        this.dataManager.set(SKIN_INDEX, index);
    }

    /**
     * Returns the skin name suffix for rendering (e.g. "_0", "_1", ...).
     */
    public String getSkinSuffix() {
        return "_" + getSkinIndex();
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

    /**
     * Deposits loot from thief's inventory into a hideout loot bag.
     * If hideout bag is full, stores excess in nearby chests (max {@code MAX_OVERFLOW_CHESTS}).
     * Beyond that cap, excess items are dropped as world entities rather than
     * griefing the world with infinite placed chests.
     */
    public void depositLootAtHideout(BlockPos hideoutPos, ItemStackHandler lootContents) {
        TileEntity te = world.getTileEntity(hideoutPos);
        if (!(te instanceof TileEntityLootBag)) {
            Thieves.LOGGER.warn("Hideout at {} no longer has loot bag!", hideoutPos);
            return;
        }

        TileEntityLootBag hideoutBag = (TileEntityLootBag) te;

        // Try to transfer all items to hideout bag
        for (int i = 0; i < lootContents.getSlots(); i++) {
            ItemStack stack = lootContents.getStackInSlot(i);
            if (!stack.isEmpty()) {
                if (!hideoutBag.addItem(stack.copy())) {
                    // Hideout bag is full — try overflow chests (Fix 5)
                    storeInNearbyChest(hideoutPos, stack.copy());
                }
            }
        }

        // Increment raid count
        hideoutBag.incrementRaidCount();

        // Clear thief's loot bag
        this.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);

        Thieves.LOGGER.info("Thief deposited loot at hideout {}. Raid count: {}",
                hideoutPos, hideoutBag.getSuccessfulRaids());
    }

    /**
     * Tries to merge {@code stack} into an existing chest near the hideout.
     * Only places a new chest if fewer than {@code MAX_OVERFLOW_CHESTS} overflow
     * chests already exist nearby. If all avenues are exhausted, the item is
     * dropped as a world entity so the player can still recover it. (Fix 5)
     */
    private static final int MAX_OVERFLOW_CHESTS = 2;

    private void storeInNearbyChest(BlockPos hideoutPos, ItemStack stack) {
        int overflowChestCount = 0;

        // Search for nearby chests within 5 blocks
        for (BlockPos checkPos : BlockPos.getAllInBox(
                hideoutPos.add(-5, -3, -5),
                hideoutPos.add(5, 3, 5))) {

            if (world.getBlockState(checkPos).getBlock() == Blocks.CHEST) {
                overflowChestCount++;
                TileEntity te = world.getTileEntity(checkPos);
                if (te instanceof net.minecraft.tileentity.TileEntityChest) {
                    net.minecraft.tileentity.TileEntityChest chest = (net.minecraft.tileentity.TileEntityChest) te;

                    // Try to merge into an existing stack first
                    for (int i = 0; i < chest.getSizeInventory(); i++) {
                        ItemStack chestStack = chest.getStackInSlot(i);
                        if (chestStack.isItemEqual(stack) && chestStack.getCount() + stack.getCount() <= chestStack.getMaxStackSize()) {
                            chestStack.grow(stack.getCount());
                            chest.markDirty();
                            Thieves.LOGGER.debug("Merged overflow item into existing chest at {}", checkPos);
                            return;
                        }
                    }

                    // Try to place into an empty slot
                    for (int i = 0; i < chest.getSizeInventory(); i++) {
                        if (chest.getStackInSlot(i).isEmpty()) {
                            chest.setInventorySlotContents(i, stack);
                            chest.markDirty();
                            Thieves.LOGGER.debug("Stored overflow item in existing chest at {}", checkPos);
                            return;
                        }
                    }
                }
            }
        }

        // Only place a new chest if we haven't hit the cap
        if (overflowChestCount < MAX_OVERFLOW_CHESTS) {
            BlockPos chestPos = findAdjacentAirBlock(hideoutPos);
            if (chestPos != null) {
                world.setBlockState(chestPos, Blocks.CHEST.getDefaultState());
                TileEntity te = world.getTileEntity(chestPos);
                if (te instanceof net.minecraft.tileentity.TileEntityChest) {
                    net.minecraft.tileentity.TileEntityChest chest = (net.minecraft.tileentity.TileEntityChest) te;
                    chest.setInventorySlotContents(0, stack);
                    chest.markDirty();
                    Thieves.LOGGER.info("Placed overflow chest at {} for excess hideout items", chestPos);
                    return;
                }
            }
        }

        // All storage avenues exhausted — drop the item as a world entity so
        // the player can still recover it without the hideout growing indefinitely.
        net.minecraft.entity.item.EntityItem dropped = new net.minecraft.entity.item.EntityItem(
                world, hideoutPos.getX() + 0.5, hideoutPos.getY() + 1.0, hideoutPos.getZ() + 0.5, stack);
        world.spawnEntity(dropped);
        Thieves.LOGGER.warn("Hideout storage full near {}. Dropping {} as world entity.", hideoutPos, stack.getDisplayName());
    }

    /**
     * Finds an air block adjacent to or near a position suitable for chest placement.
     */
    private BlockPos findAdjacentAirBlock(BlockPos center) {
        // Check immediate adjacent blocks first
        for (BlockPos offset : new BlockPos[]{
                center.north(), center.south(), center.east(), center.west(),
                center.up(), center.down()
        }) {
            if (world.isAirBlock(offset) && canPlaceChest(offset)) {
                return offset;
            }
        }

        // Check in a 3x3x3 area
        for (BlockPos checkPos : BlockPos.getAllInBox(
                center.add(-2, -2, -2),
                center.add(2, 2, 2))) {
            if (world.isAirBlock(checkPos) && canPlaceChest(checkPos)) {
                return checkPos;
            }
        }

        return null;
    }

    /**
     * Checks if a chest can be placed at this position (needs solid ground below).
     */
    private boolean canPlaceChest(BlockPos pos) {
        BlockPos below = pos.down();
        IBlockState belowState = world.getBlockState(below);
        return belowState.isSideSolid(world, below, net.minecraft.util.EnumFacing.UP);
    }

	@Override
	public void onDeath(net.minecraft.util.DamageSource cause) {
		super.onDeath(cause);
		if (!this.world.isRemote && cause.getTrueSource() instanceof net.minecraft.entity.player.EntityPlayer) {
			// Chance to drop map revealing hideout location
			float chance = 0.15F;
			if (this instanceof EntityMasterThief) chance = 0.5F;
			
			if (this.world.rand.nextFloat() < chance) {
				net.minecraft.util.math.BlockPos hideout = com.windanesz.thieves.world.ThiefStashManager.get(this.world)
					.getNearestHideout(this.world, this.getPosition(), 500);
				
				if (hideout != null) {
					net.minecraft.item.ItemStack map = com.windanesz.thieves.item.ItemStashMap.createMap(hideout);
					this.entityDropItem(map, 0.0F);
					Thieves.LOGGER.info("Thief dropped a stash map to hideout at {}", hideout);
				}
			}
		}
	}

    /**
     * Equips the thief with a weapon based on raid count and updates combat AI to match.
     * Raid count 0-1: No weapon (melee AI)
     * Raid count 2-3: Wooden sword (melee AI)
     * Raid count 4-5: Stone sword, 30% chance for bow
     * Raid count 6+:  Iron sword, 50% chance for bow
     * Also equips shields (raid 3+, melee only) and armor (leather at raid 4+, iron at raid 7+).
     */
    public void equipWeaponBasedOnRaidCount(int raidCount) {
        if (raidCount <= 1) {
            applyCombatAI(false);
            return; // No weapon for first raid
        }

        boolean useBow = false;

        // Determine if thief uses bow (higher raid count = higher chance)
        if (raidCount >= 6) {
            useBow = world.rand.nextFloat() < 0.5F; // 50% chance at raid 6+
        } else if (raidCount >= 4) {
            useBow = world.rand.nextFloat() < 0.3F; // 30% chance at raid 4-5
        }

        // Equip weapon
        if (useBow) {
            this.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            // Give infinite arrows (tipped arrow in offhand would be consumed, so we rely on IRangedAttackMob spawning arrows)
        } else {
            ItemStack weapon;
            if (raidCount <= 2) {
                weapon = new ItemStack(Items.WOODEN_SWORD);
            } else if (raidCount <= 4) {
                weapon = new ItemStack(Items.STONE_SWORD);
            } else {
                weapon = new ItemStack(Items.IRON_SWORD);
            }
            this.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, weapon);

            // Equip shield (raid 3+, only for melee thieves)
            if (raidCount >= 3) {
                this.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            }
        }

        // Update combat AI to match the equipped weapon (Fix 3)
        applyCombatAI(useBow);
    }
}
