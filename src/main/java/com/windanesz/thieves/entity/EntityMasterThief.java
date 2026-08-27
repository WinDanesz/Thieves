package com.windanesz.thieves.entity;

import com.windanesz.thieves.Thieves;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Elite thief variant with enhanced stats and capabilities.
 * Spawns after player has experienced 5+ robberies.
 */
public class EntityMasterThief extends EntityThief {

	public static final ResourceLocation LOOT_TABLE = new ResourceLocation(Thieves.MODID, "entities/master_thief");
	
	private static final int MAX_CARRYING_CAPACITY = 32; // vs 16 for regular thieves

	public EntityMasterThief(World worldIn) {
		super(worldIn);
		this.setSize(0.5F, 1.9F); // Slightly taller
	}

	@Override
	protected void applyEntityAttributes() {
		super.applyEntityAttributes();
		// Enhanced attributes
		this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(30.0D); // vs 20.0
		this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.25D); // vs 0.2
		this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(5.0D); // vs 3.0
		this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(40.0D); // vs 32.0
		this.getEntityAttribute(SharedMonsterAttributes.ARMOR).setBaseValue(4.0D); // Additional armor
	}

	/**
	 * Master thieves can carry more items than regular thieves.
	 */
	public int getMaxCarryingCapacity() {
		return MAX_CARRYING_CAPACITY;
	}

	/**
	 * Master thieves move faster when stealing.
	 */
	public double getStealingSpeedMultiplier() {
		return 1.5D;
	}

	/**
	 * Master thieves prioritize valuable items.
	 */
	public boolean prioritizesValuableItems() {
		return true;
	}

	@Override
	protected void setEquipmentBasedOnDifficulty(DifficultyInstance difficulty) {
		super.setEquipmentBasedOnDifficulty(difficulty);

		// Master thieves always have better equipment
		this.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		
		// Leather armor for visual distinction
		this.setItemStackToSlot(EntityEquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		this.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
		this.setItemStackToSlot(EntityEquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
		this.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));

		// Set armor to not drop
		for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
			if (slot.getSlotType() == EntityEquipmentSlot.Type.ARMOR) {
				this.setDropChance(slot, 0.0F);
			}
		}
	}

	@Override
	protected ResourceLocation getLootTable() {
		return LOOT_TABLE;
	}

	@Override
	public int getTalkInterval() {
		return 120; // More vocal than regular thieves
	}

	/**
	 * Master thieves have a distinctive appearance - could be used for rendering.
	 */
	public boolean isMasterThief() {
		return true;
	}

	@Override
	protected float getSoundVolume() {
		return 0.6F; // Slightly louder
	}

	@Override
	protected float getSoundPitch() {
		return 0.9F; // Slightly deeper voice
	}

	// --- BREACHING LOGIC --- //

	private int blocksBroken = 0;
	private int breachCooldown = 0;
	private static final int MAX_BLOCKS_BROKEN = 6;

	public boolean canBreach() {
		return blocksBroken < MAX_BLOCKS_BROKEN;
	}

	@Override
	public void onLivingUpdate() {
		super.onLivingUpdate();

		if (!this.world.isRemote && this.isEntityAlive()) {
			if (breachCooldown > 0) {
				breachCooldown--;
			}

			// If we are stuck (collided horizontally) and we still have blocks to break
			if (this.isCollidedHorizontally && this.canBreach() && breachCooldown == 0) {
				// Determine block in front of us
				net.minecraft.util.math.Vec3d look = this.getLookVec();
				// Offset slightly forward to get the block we're bumping into
				net.minecraft.util.math.BlockPos headPos = new net.minecraft.util.math.BlockPos(this.posX + look.x, this.posY + this.getEyeHeight(), this.posZ + look.z);
				net.minecraft.util.math.BlockPos footPos = new net.minecraft.util.math.BlockPos(this.posX + look.x, this.posY + 0.1, this.posZ + look.z);
				
				boolean brokeSomething = false;
				
				// Try breaking head-level and foot-level blocks
				if (tryBreachBlock(headPos)) brokeSomething = true;
				if (tryBreachBlock(footPos)) brokeSomething = true;
				
				if (brokeSomething) {
					this.breachCooldown = 30; // 1.5 seconds between breaks
					this.blocksBroken++;
				}
			}
		}
	}

	private boolean tryBreachBlock(net.minecraft.util.math.BlockPos pos) {
		net.minecraft.block.state.IBlockState state = this.world.getBlockState(pos);
		net.minecraft.block.Block block = state.getBlock();
		
		if (this.world.isAirBlock(pos)) return false;
		if (state.getBlockHardness(this.world, pos) < 0.0F) return false; // Unbreakable (Bedrock)
		if (state.getBlockHardness(this.world, pos) > 5.0F) return false; // Too hard (Obsidian)
		
		// Don't break inventories, we want to steal from them!
		if (this.world.getTileEntity(pos) != null) return false;
		
		if (block == net.minecraft.init.Blocks.IRON_DOOR || state.getMaterial().blocksMovement()) {
			boolean canGrief = net.minecraftforge.event.ForgeEventFactory.getMobGriefingEvent(this.world, this);
			if (!canGrief) return false;

			// Play breaking sound/particles
			this.world.playEvent(2001, pos, net.minecraft.block.Block.getStateId(state));
			this.world.destroyBlock(pos, true);
			return true;
		}
		return false;
	}
}
