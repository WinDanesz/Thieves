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
}
