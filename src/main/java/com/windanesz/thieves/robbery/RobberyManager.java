package com.windanesz.thieves.robbery;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.capability.PlayerCapability;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.entity.ai.ThiefAISeekChestAndSignal;
import com.windanesz.thieves.util.PlayerBaseDetector;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Central tracker for all active robberies.
 *
 * <h3>Multi-player</h3>
 * <p>The {@code activeRobberies} list can hold one entry per player, so
 * simultaneous robberies work without interfering.  Chunk loading is managed
 * by {@link RobberyChunkManager}, which now keeps one ticket <em>per player</em>
 * keyed by UUID.</p>
 *
 * <h3>World close / reload continuity</h3>
 * <ul>
 *   <li>On world unload we release chunk tickets only — thieves are
 *       <strong>not</strong> despawned.  Minecraft serialises all living
 *       entities to {@code level.dat} / region files on a clean stop, so they
 *       will be exactly where the player left them on the next load.</li>
 *   <li>Re-triggering is prevented because
 *       {@link com.windanesz.thieves.capability.PlayerCapability#startRobbery}
 *       stamps {@code lastRobberyTime} and resets progress the moment thieves
 *       spawn — before any save can happen.</li>
 *   <li>Reloaded robbery thieves whose {@code robberyLootBagPos} is set (saved
 *       to NBT) continue escaping immediately via their always-registered
 *       {@code ThiefAIEscapeWithLoot} task.</li>
 *   <li>Reloaded robbery thieves that were still <em>seeking</em> a chest
 *       (no loot bag yet) get their {@link ThiefAISeekChestAndSignal} task
 *       re-added via {@link #onEntityJoinWorld(EntityJoinWorldEvent)}.  They
 *       re-scan the player's base for chests and resume the robbery.</li>
 *   <li>True orphans (e.g. from a crash before {@code startRobbery()} stamped
 *       the cooldown) self-despawn via the counter in
 *       {@link EntityThief#onLivingUpdate()} after 10 minutes.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Thieves.MODID)
public class RobberyManager {

    private static final List<ActiveRobbery> activeRobberies = new ArrayList<>();

    // ------------------------------------------------------------------ API

    /**
     * Registers a newly started robbery so it will be tracked for timeout.
     * Marks all spawned thieves as claimed so the orphan-despawn counter
     * is suppressed.
     */
    public static void register(ActiveRobbery robbery) {
        activeRobberies.add(robbery);
        for (EntityThief thief : robbery.thieves) {
            thief.claimedByRobbery = true;
        }
        Thieves.LOGGER.debug("RobberyManager: Registered robbery for player {}. Timeout at tick {}.",
                robbery.player.getName(), robbery.timeoutTick);
    }

    /**
     * Called every tick from RobberyEventHandler.  Checks each active robbery
     * for completion or timeout and cleans up accordingly.
     */
    public static void tick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Iterator<ActiveRobbery> it = activeRobberies.iterator();
        while (it.hasNext()) {
            ActiveRobbery robbery = it.next();

            // Only process robberies in the matching world
            if (robbery.world != event.world) continue;

            long currentTick = robbery.world.getTotalWorldTime();

            if (robbery.lootBagPlaced) {
                // Loot bag is in the world; thieves know where it is via
                // their robberyLootBagPos field.  Hand off — the existing
                // ThiefAIEscapeWithLoot and ThiefAIPickupLootBag tasks take over.
                Thieves.LOGGER.info("RobberyManager: Robbery for player {} succeeded. Loot bag at {}.",
                        robbery.player.getName(), robbery.lootBagPos);
                RobberyChunkManager.endRobbery(robbery.playerUUID);
                it.remove();

            } else if (currentTick >= robbery.timeoutTick) {
                // Robbery timed out — thieves failed to reach any chest
                for (EntityThief thief : robbery.thieves) {
                    if (!thief.isDead) {
                        thief.setDead();
                    }
                }
                if (robbery.player.isEntityAlive()) {
                    robbery.player.sendMessage(new TextComponentString(
                            TextFormatting.GRAY + "The thieves couldn't reach your chests and retreated."));
                }
                Thieves.LOGGER.info("RobberyManager: Robbery for player {} timed out. Thieves despawned.",
                        robbery.player.getName());
                RobberyChunkManager.endRobbery(robbery.playerUUID);
                it.remove();
            }
        }
    }

    /** Returns the number of currently tracked active robberies. */
    public static int getActiveRobberyCount() {
        return activeRobberies.size();
    }

    // ------------------------------------------------------------------ Events

    /**
     * On world unload (including server stop): release all chunk tickets so
     * Forge's quota is freed.  We do NOT despawn robbery thieves here —
     * Minecraft serialises living entities to disk on a clean shutdown, so
     * players will find the same mobs on reload.  The {@code startRobbery()}
     * cooldown stamp already prevents a re-trigger.
     */
    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) return;

        Iterator<ActiveRobbery> it = activeRobberies.iterator();
        while (it.hasNext()) {
            ActiveRobbery robbery = it.next();
            if (robbery.world != event.getWorld()) continue;

            // Release the chunk ticket — entities survive via normal serialisation
            RobberyChunkManager.endRobbery(robbery.playerUUID);
            Thieves.LOGGER.info(
                    "RobberyManager: World unloading — released chunk ticket for player {}. " +
                    "Thieves will be saved and restored on next load.",
                    robbery.player.getName());
            it.remove();
        }

        // Belt-and-braces: release any stale tickets whose robbery entry was
        // already removed but whose ticket somehow wasn't freed
        RobberyChunkManager.releaseAllForWorld(event.getWorld());
    }

    /**
     * Halts the robbery and despawns thieves if the player logs out.
     * This prevents "offline raiding" where forced-loaded chunks allow
     * thieves to rob the base while the player is offline.
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player.world.isRemote) return;

        Iterator<ActiveRobbery> it = activeRobberies.iterator();
        while (it.hasNext()) {
            ActiveRobbery robbery = it.next();
            if (robbery.playerUUID.equals(event.player.getUniqueID())) {
                for (EntityThief thief : robbery.thieves) {
                    if (!thief.isDead) {
                        thief.setDead();
                    }
                }
                RobberyChunkManager.endRobbery(robbery.playerUUID);
                Thieves.LOGGER.info("RobberyManager: Player {} logged out. Robbery halted.", event.player.getName());
                it.remove();
                break;
            }
        }
    }

    /**
     * When a robbery thief entity loads (either on first spawn or after a world
     * reload), check whether it needs its seek-chest AI re-assigned.
     *
     * <ul>
     *   <li>Fresh spawn: already has the task added by
     *       {@link RobberySpawner#triggerRobbery} right after entity creation.
     *       {@code claimedByRobbery} is true, so nothing extra happens here.</li>
     *   <li>Reloaded with loot bag pos set: the thief was mid-escape; the
     *       always-registered {@code ThiefAIEscapeWithLoot} task picks it up
     *       automatically.  No action needed.</li>
     *   <li>Reloaded without loot bag pos: the thief was still seeking a chest
     *       when the world closed.  We re-scan the owner player's base and
     *       re-add {@link ThiefAISeekChestAndSignal} so it can resume.</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getEntity() instanceof EntityThief)) return;

        EntityThief thief = (EntityThief) event.getEntity();

        // Only act on robbery thieves that loaded from NBT (not fresh spawns)
        if (!thief.isRobberyThief() || thief.claimedByRobbery) return;

        // Thief has a loot bag position already — ThiefAIEscapeWithLoot will
        // handle it.  Mark claimed so the orphan counter stays quiet.
        if (thief.getRobberyLootBagPos() != null) {
            thief.claimedByRobbery = true;
            Thieves.LOGGER.debug(
                    "RobberyManager: Reloaded thief at {} has loot bag pos {}. Resuming escape.",
                    thief.getPosition(), thief.getRobberyLootBagPos());
            return;
        }

        // Thief has no loot bag pos — it was mid-seek when the world closed.
        // Try to find the owning player and re-assign the seek-chest task.
        EntityPlayer owner = null;
        if (thief.getRobberyTargetUUID() != null) {
            owner = event.getWorld().getPlayerEntityByUUID(thief.getRobberyTargetUUID());
        }

        if (owner == null) {
            // Player is not online yet; leave unclaimed — orphan timer will
            // eventually clean up if they never log back in.
            Thieves.LOGGER.debug(
                    "RobberyManager: Reloaded seeking thief at {} — owner offline, will wait.",
                    thief.getPosition());
            return;
        }

        PlayerCapability cap = PlayerCapability.get(owner);
        if (cap == null || cap.baseLocation == null) {
            Thieves.LOGGER.debug(
                    "RobberyManager: Reloaded seeking thief — could not get base location for {}.",
                    owner.getName());
            return;
        }

        List<BlockPos> chests = PlayerBaseDetector.scanForChests(
                event.getWorld(), cap.baseLocation, 3);

        if (chests.isEmpty()) {
            Thieves.LOGGER.warn(
                    "RobberyManager: Reloaded seeking thief found no chests at base for {}. " +
                    "Thief will be orphaned and despawn.", owner.getName());
            return;
        }

        // Re-create a shared loot bag state so multiple reloaded thieves
        // from the same robbery don't all try to place a bag independently.
        // We use a simple boolean array (effectively final for lambda capture).
        final boolean[] bagPlaced = {false};
        final BlockPos[] bagPos = {null};

        // Ensure we don't add duplicate AI tasks if the thief already has one
        boolean hasTask = false;
        for (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry entry : thief.tasks.taskEntries) {
            if (entry.action instanceof ThiefAISeekChestAndSignal) {
                hasTask = true;
                break;
            }
        }

        if (!hasTask) {
            ThiefAISeekChestAndSignal.ChestReachedCallback callback = (t, chestPos) -> {
                if (bagPlaced[0]) return;

                if (t.getRobberyLootBagPos() != null) {
                    bagPlaced[0] = true;
                    return;
                }

                bagPlaced[0] = true;
                ReloadedRobberyHelper.placeLootBagAndNotify(t, chestPos, chests, owner, cap, bagPos);
            };

            thief.tasks.addTask(0, new ThiefAISeekChestAndSignal(thief, chests, callback));
        }
        thief.claimedByRobbery = true;

        Thieves.LOGGER.info(
                "RobberyManager: Re-assigned seek-chest AI to reloaded thief at {} for player {}.",
                thief.getPosition(), owner.getName());
    }

}
