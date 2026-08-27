package com.windanesz.thieves.entity.ai;

import com.windanesz.thieves.Settings;
import com.windanesz.thieves.entity.EntityThief;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.List;

/**
 * AI task for thieves to pathfind to the nearest chest and signal when reached.
 */
public class ThiefAISeekChestAndSignal extends EntityAIBase {
    private final EntityThief thief;
    private final List<BlockPos> chestLocations;
    private final World world;
    private BlockPos targetChest;
    private long startTime;
    private boolean signaled = false;

    public interface ChestReachedCallback {
        void onChestReached(EntityThief thief, BlockPos chestPos);
    }
    private final ChestReachedCallback callback;

    public ThiefAISeekChestAndSignal(EntityThief thief, List<BlockPos> chestLocations, ChestReachedCallback callback) {
        this.thief = thief;
        this.chestLocations = chestLocations;
        this.world = thief.world;
        this.callback = callback;
    }

    @Override
    public boolean shouldExecute() {
        if (!thief.isRobberyThief() || thief.getRobberyLootBagPos() != null) {
            return false;
        }
        // Find the nearest chest
        double minDist = Double.MAX_VALUE;
        BlockPos nearest = null;
        for (BlockPos pos : chestLocations) {
            double dist = thief.getDistanceSq(pos);
            if (dist < minDist) {
                minDist = dist;
                nearest = pos;
            }
        }
        if (nearest != null) {
            targetChest = nearest;
            return true;
        }
        return false;
    }

    @Override
    public void startExecuting() {
        startTime = world.getTotalWorldTime();
        thief.getNavigator().tryMoveToXYZ(targetChest.getX() + 0.5, targetChest.getY() + 0.5, targetChest.getZ() + 0.5, 1.0);
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (signaled || thief.getRobberyLootBagPos() != null) return false;
        long timeoutTicks = 20 * Settings.robbery.robberyTimeout;
        if (world.getTotalWorldTime() - startTime > timeoutTicks) {
            com.windanesz.thieves.Thieves.LOGGER.info("Thief at {} timed out while seeking chest, despawning.", thief.getPosition());
            thief.setDead();
            return false;
        }
        return !thief.getNavigator().noPath();
    }

    @Override
    public void updateTask() {
        if (signaled) return;
        if (thief.getDistanceSq(targetChest) < 4.0) { // within 2 blocks
            signaled = true;
            callback.onChestReached(thief, targetChest);
        }
    }

    @Override
    public void resetTask() {
        thief.getNavigator().clearPath();
    }
}
