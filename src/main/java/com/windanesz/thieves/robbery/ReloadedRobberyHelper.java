package com.windanesz.thieves.robbery;

import com.windanesz.thieves.Settings;
import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.Utils;
import com.windanesz.thieves.capability.PlayerCapability;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.init.ModBlocks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Random;

/**
 * Shared utility for placing the robbery loot bag when a thief reaches a chest.
 *
 * <p>This logic is identical whether the robbery is fresh (handled by
 * {@link RobberySpawner}) or resumed after a world reload (handled by
 * {@link RobberyManager#onEntityJoinWorld}).  Extracting it here prevents
 * duplication and ensures both paths behave the same.</p>
 */
public class ReloadedRobberyHelper {

    private static final Random RANDOM = new Random();

    /**
     * Places the robbery loot bag near {@code chestPos}, notifies all nearby
     * robbery thieves of its position, and marks the robbery as complete in
     * the player capability.
     *
     * <p>Called from the {@link com.windanesz.thieves.entity.ai.ThiefAISeekChestAndSignal}
     * callback for <em>reloaded</em> robbery thieves (thieves that were
     * mid-seek when the world closed and have just rejoined the world).</p>
     *
     * @param thief        The thief that reached the chest.
     * @param chestPos     The chest that was reached.
     * @param allChests    All chest positions at the base (for context).
     * @param player       The player being robbed.
     * @param cap          The player's robbery capability.
     * @param bagPosOut    Single-element array used to return the placed bag
     *                     position back to the caller (workaround for
     *                     effectively-final lambda capture).
     */
    public static void placeLootBagAndNotify(
            EntityThief thief,
            BlockPos chestPos,
            List<BlockPos> allChests,
            EntityPlayer player,
            PlayerCapability cap,
            BlockPos[] bagPosOut) {

        World world = thief.world;

        // Choose a random placement position near the chest
        int min = Settings.robbery.lootBagSpawnMinDistance;
        int max = Settings.robbery.lootBagSpawnMaxDistance;
        int dist = min + RANDOM.nextInt(max - min + 1);

        double angle = RANDOM.nextDouble() * Math.PI * 2;
        int offsetX = (int) (Math.cos(angle) * dist);
        int offsetZ = (int) (Math.sin(angle) * dist);
        BlockPos candidate = chestPos.add(offsetX, 0, offsetZ);
        BlockPos lootBagPos = world.getHeight(candidate);

        if (!world.isAirBlock(lootBagPos)) {
            BlockPos safePos = Utils.findNearbyAirSpace(world, lootBagPos, 3);
            if (safePos != null) {
                lootBagPos = safePos;
            }
        }

        world.setBlockState(lootBagPos, ModBlocks.LOOT_BAG.getDefaultState());
        Thieves.LOGGER.info(
                "ReloadedRobberyHelper: Placed loot bag at {} (reloaded robbery, thief reached chest {}).",
                lootBagPos, chestPos);

        // Write position back so the caller can share it with sibling thieves
        if (bagPosOut != null) {
            bagPosOut[0] = lootBagPos;
        }

        // Notify all robbery thieves in the same area of the loot bag location.
        // We check distance to avoid hijacking thieves from another player's 
        // simultaneous robbery on a multiplayer server.
        final BlockPos finalPos = lootBagPos;
        world.loadedEntityList.stream()
                .filter(e -> e instanceof EntityThief)
                .map(e -> (EntityThief) e)
                .filter(EntityThief::isRobberyThief)
                .filter(t -> t.getRobberyLootBagPos() == null)
                .filter(t -> t.getDistanceSqToCenter(finalPos) < 25000.0D) // ~158 blocks radius
                .forEach(t -> t.setRobberyLootBagPos(finalPos));

        // Increment the completed-robbery counter for difficulty scaling.
        // startRobbery() already reset progress and stamped the cooldown, so
        // completeRobbery() here only increments the counter.
        cap.completeRobbery(world);
    }
}
