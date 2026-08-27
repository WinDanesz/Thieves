package com.windanesz.thieves.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraft.world.storage.loot.LootTable;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Collections;
import java.util.List;

public class BlockLootBag extends BlockContainer {
	public static final PropertyDirection FACING = PropertyDirection.create("facing", EnumFacing.Plane.HORIZONTAL);
	public static final PropertyBool SNOWY = PropertyBool.create("snowy");
	public ResourceLocation lootTable;
	public AxisAlignedBB boundingBox;
	protected boolean shouldSpawnGoblins = false;

	public BlockLootBag(Material materialmaterialn) {
		super(materialmaterialn);
		this.setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH).withProperty(SNOWY, false));
		setHardness(1.5F);
		setResistance(5.0F);
		this.boundingBox = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D); // Default AABB
	}

	public BlockLootBag setSpawnGoblins(boolean shouldSpawnGoblins) {
		this.shouldSpawnGoblins = shouldSpawnGoblins;
		return this;
	}

	@Override
	public TileEntity createNewTileEntity(World world, int meta) {
		return new TileEntityLootBag();
	}

	@Override
	public AxisAlignedBB getBoundingBox(IBlockState state, net.minecraft.world.IBlockAccess source, BlockPos pos) {
		return this.boundingBox;
	}

	public BlockLootBag setBoundingBox(AxisAlignedBB boundingBox) {
		this.boundingBox = boundingBox;
		return this;
	}

	@Override
	public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, int meta, EntityLivingBase placer) {
		boolean isSnowy = worldIn.getBiome(pos).isSnowyBiome();
		return this.getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite())
				.withProperty(SNOWY, isSnowy);
	}

	@Override
	public boolean isFullBlock(IBlockState state) {
		return false;
	}

	@Override
	public boolean isOpaqueCube(IBlockState state) {
		return false;
	}

	@Override
	public boolean isFullCube(IBlockState state) {
		return false;
	}

	@Override
	public boolean isSideSolid(IBlockState base_state, net.minecraft.world.IBlockAccess world, BlockPos pos, EnumFacing side) {
		return false;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public BlockRenderLayer getRenderLayer() {
		return BlockRenderLayer.CUTOUT;
	}

	@Override
	public int getMetaFromState(IBlockState state) {
		int i = state.getValue(FACING).getHorizontalIndex();

		if (state.getValue(SNOWY)) {
			i |= 4;
		}

		return i;
	}

	@Override
	public IBlockState getStateFromMeta(int meta) {
		return getDefaultState()
				.withProperty(FACING, EnumFacing.byHorizontalIndex(meta & 3))
				.withProperty(SNOWY, (meta & 4) != 0);
	}

	@Override
	public IBlockState getActualState(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
		return state.withProperty(SNOWY, worldIn.getBiome(pos).isSnowyBiome());
	}

	@Override
	protected BlockStateContainer createBlockState() {
		return new BlockStateContainer(this, FACING, SNOWY);
	}

	@Override
	public EnumBlockRenderType getRenderType(IBlockState state) {
		return EnumBlockRenderType.MODEL;
	}

	@Override
	public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, 
									EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		if (worldIn.isRemote) {
			return true;
		}

		TileEntity te = worldIn.getTileEntity(pos);
		if (!(te instanceof TileEntityLootBag)) {
			return false;
		}

		TileEntityLootBag lootBag = (TileEntityLootBag) te;
		ItemStack heldItem = playerIn.getHeldItem(hand);

		if (playerIn.isSneaking()) {
			// Sneak + right-click: Remove random item
			ItemStack removed = lootBag.removeRandomItem();
			if (!removed.isEmpty()) {
				if (!playerIn.inventory.addItemStackToInventory(removed)) {
					// If player inventory is full, drop it
					EntityItem entityItem = new EntityItem(worldIn, playerIn.posX, playerIn.posY, playerIn.posZ, removed);
					worldIn.spawnEntity(entityItem);
				}
				playerIn.sendMessage(new TextComponentTranslation("message.thieves.loot_bag.removed", removed.getDisplayName()));
				return true;
			} else {
				playerIn.sendMessage(new TextComponentTranslation("message.thieves.loot_bag.empty"));
				return true;
			}
		} else {
			// Right-click: Add held item
			if (!heldItem.isEmpty()) {
				ItemStack toAdd = heldItem.copy();
				if (lootBag.addItem(toAdd)) {
					// Successfully added
					heldItem.shrink(heldItem.getCount()); // It completely consumed the stack
					playerIn.sendMessage(new TextComponentTranslation("message.thieves.loot_bag.added", toAdd.getDisplayName()));
					return true;
				} else {
					playerIn.sendMessage(new TextComponentTranslation("message.thieves.loot_bag.full"));
					return true;
				}
			}
		}

		return false;
	}

	@Override
	public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
		if (!worldIn.isRemote) {
			// Drop loot bag contents when broken
			TileEntity te = worldIn.getTileEntity(pos);
			if (te instanceof TileEntityLootBag) {
				TileEntityLootBag lootBag = (TileEntityLootBag) te;
				for (ItemStack stack : lootBag.getAllItems()) {
					if (!stack.isEmpty()) {
						spawnAsEntity(worldIn, pos, stack);
					}
				}
				// Unregister hideout when the block is physically broken
				com.windanesz.thieves.world.ThiefStashManager manager = com.windanesz.thieves.world.ThiefStashManager.get(worldIn);
				if (lootBag.isHideout()) {
					manager.removeHideout(worldIn.provider.getDimension(), pos);
					Thieves.LOGGER.info("Hideout at {} was destroyed and unregistered", pos);
				}
				// Also clean up if it was a stash
				manager.removeStash(worldIn, pos);
			}
		}
		super.breakBlock(worldIn, pos, state);
	}

	@Override
	public List<ItemStack> getDrops(net.minecraft.world.IBlockAccess iBlockAccess, BlockPos pos, IBlockState state, int fortune) {
		if (this.lootTable == null) {
			return Collections.emptyList();
		}
		
		if (!(iBlockAccess instanceof WorldServer) || ((WorldServer) iBlockAccess).isRemote) {
			return Collections.emptyList();
		}

		World realWorld = (World) iBlockAccess;
		LootTable lootTable = realWorld.getLootTableManager().getLootTableFromLocation(this.lootTable);
		LootContext.Builder builder = new LootContext.Builder((WorldServer) realWorld);
		builder.withLuck(0);
		// Optionally add more context (e.g., player, tile entity)
		return lootTable.generateLootForPools(realWorld.rand, builder.build());
	}

	public ResourceLocation getLootTable() {
		return this.lootTable;
	}

	public BlockLootBag setLootTable(ResourceLocation lootTable) {
		this.lootTable = lootTable;
		return this;
	}
}
