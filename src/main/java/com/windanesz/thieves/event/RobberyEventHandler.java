package com.windanesz.thieves.event;

import com.windanesz.thieves.Settings;
import com.windanesz.thieves.capability.PlayerCapability;
import com.windanesz.thieves.robbery.RobberyManager;
import com.windanesz.thieves.robbery.RobberySpawner;
import com.windanesz.thieves.util.PlayerBaseDetector;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import com.windanesz.thieves.entity.EntityThief;
import com.google.common.base.Predicate;
import javax.annotation.Nullable;

/**
 * Event handler for the robbery system.
 * Handles progress updates, base detection, and robbery triggers.
 */
@Mod.EventBusSubscriber
public class RobberyEventHandler {

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!event.getWorld().isRemote && event.getEntity() instanceof EntityWolf) {
            EntityWolf wolf = (EntityWolf) event.getEntity();
            
            // Check if the task already exists to prevent duplicate tasks piling up
            boolean hasTask = false;
            for (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry entry : wolf.targetTasks.taskEntries) {
                if (entry.action instanceof EntityAINearestAttackableTarget) {
                    EntityAINearestAttackableTarget targetTask = (EntityAINearestAttackableTarget) entry.action;
                    if (targetTask.targetClass == EntityThief.class) {
                        hasTask = true;
                        break;
                    }
                }
            }
            
            if (!hasTask) {
                wolf.targetTasks.addTask(5, new EntityAINearestAttackableTarget<EntityThief>(wolf, EntityThief.class, 10, true, false, new Predicate<EntityThief>() {
                    @Override
                    public boolean apply(@Nullable EntityThief thief) {
                        return wolf.isTamed();
                    }
                }));
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // Server-side only, End phase only
        if (event.phase != TickEvent.Phase.END || event.player.world.isRemote) {
            return;
        }

        EntityPlayer player = event.player;
        PlayerCapability cap = PlayerCapability.get(player);

        if (cap == null) {
            return;
        }

        // Every 20 ticks (1 second)
        if (player.ticksExisted % 20 == 0) {
            // Track chunk visits
            ChunkPos currentChunk = new ChunkPos(player.getPosition());
            cap.addChunkVisit(currentChunk, player.world.getTotalWorldTime());

            // Update robbery progress (if base is detected)
            if (cap.baseLocation != null) {
                float progressIncrease = calculateProgressIncrease(cap);
                cap.incrementRobberyProgress(progressIncrease); // Already converted to per-second by calculateProgressIncrease
            }
        }

        // Every 12000 ticks (10 minutes) — update base detection
        if (player.ticksExisted % 12000 == 0) {
            PlayerBaseDetector.updateBaseDetection(player, cap);
        }

        // Check for robbery trigger (every 100 ticks to reduce overhead)
        if (player.ticksExisted % 100 == 0) {
            if (cap.canTriggerRobbery(player.world)) {
                RobberySpawner.triggerRobbery(player, cap);
            }

            // Check for scout spawn
            //if (cap.shouldSpawnScout(player.world)) {
            //    RobberySpawner.spawnScout(player, cap);
            //}
        }
    }

    /**
     * Drives the central RobberyManager — checks timeout and completion
     * for all active robberies. Replaces the old per-robbery anonymous listeners.
     */
    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.world.isRemote) return;
        RobberyManager.tick(event);
    }

    /**
     * Calculates the per-second progress increase based on chest value.
     */
    private static float calculateProgressIncrease(PlayerCapability cap) {
        float baseRate = Settings.robbery.baseProgressRate;
        float maxRate = Settings.robbery.maxProgressRate;
        float valueScore = cap.chestValueScore;

        // Scale progress based on chest value (0-1000 value score range)
        // Higher value = faster buildup
        float scaledRate = baseRate + ((maxRate - baseRate) * Math.min(valueScore / 1000.0F, 1.0F));

        return scaledRate / 60.0F; // Convert per-minute to per-second
    }
}
