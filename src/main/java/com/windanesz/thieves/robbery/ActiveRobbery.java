package com.windanesz.thieves.robbery;

import com.windanesz.thieves.entity.EntityThief;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.UUID;

/**
 * Represents one in-progress robbery event.
 * Passed to RobberyManager so the tick handler can monitor timeout
 * and clean up without relying on anonymous inner-class event listeners.
 */
public class ActiveRobbery {

    /** UUID of the player being robbed — used as the chunk ticket key. */
    public final UUID playerUUID;

    /** The player being robbed. */
    public final EntityPlayer player;

    /** The world the robbery is taking place in. */
    public final World world;

    /** All thieves spawned for this robbery. */
    public final List<EntityThief> thieves;

    /**
     * World time tick at which the robbery is considered failed if
     * no loot bag has been placed yet.
     */
    public final long timeoutTick;

    /** Set to true once a thief reaches a chest and the loot bag is placed. */
    public boolean lootBagPlaced = false;

    /** Position of the placed loot bag, once {@link #lootBagPlaced} is true. */
    public BlockPos lootBagPos = null;

    public ActiveRobbery(UUID playerUUID, EntityPlayer player, World world, List<EntityThief> thieves, long timeoutTick) {
        this.playerUUID = playerUUID;
        this.player = player;
        this.world = world;
        this.thieves = thieves;
        this.timeoutTick = timeoutTick;
    }
}
