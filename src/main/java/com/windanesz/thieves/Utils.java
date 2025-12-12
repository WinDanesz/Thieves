package com.windanesz.thieves;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Random;

public class Utils {

	/**
	 * Finds a nearby safe airspace around a central point.
	 *
	 * @param world       The world to search in.
	 * @param startPos    The center position to search around.
	 * @param maxDistance The maximum horizontal distance to search.
	 * @return A suitable {@link BlockPos} for an entity to stand, or {@code null} if none was found within the search area.
	 */
	public static BlockPos findNearbyAirSpace(World world, BlockPos startPos, int maxDistance) {
		Random random = new Random();

		for (int i = 0; i < 100; i++) { // Limit search attempts
			int x = startPos.getX() + random.nextInt(maxDistance * 2 + 1) - maxDistance;
			int z = startPos.getZ() + random.nextInt(maxDistance * 2 + 1) - maxDistance;

			// Search from the start Y level up and down a bit
			for (int yOffset = 0; yOffset <= 5; yOffset++) {
				if (tryPos(world, new BlockPos(x, startPos.getY() + yOffset, z))) {
					return new BlockPos(x, startPos.getY() + yOffset, z);
				}
				if (yOffset != 0 && tryPos(world, new BlockPos(x, startPos.getY() - yOffset, z))) {
					return new BlockPos(x, startPos.getY() - yOffset, z);
				}
			}
		}

		return null;
	}

	/**
	 * Checks if a BlockPos is a valid standing position (solid ground, two air blocks above).
	 *
	 * @param world The world.
	 * @param pos   The BlockPos to check.
	 * @return true if the position is safe to stand on.
	 */
	private static boolean tryPos(World world, BlockPos pos) {
		IBlockState groundState = world.getBlockState(pos.down());
		IBlockState feetState = world.getBlockState(pos);
		IBlockState headState = world.getBlockState(pos.up());

		// Check for solid ground and two blocks of air for the entity
		return groundState.isSideSolid(world, pos.down(), EnumFacing.UP) &&
				feetState.getBlock().isAir(feetState, world, pos) &&
				headState.getBlock().isAir(headState, world, pos.up());
	}

}
