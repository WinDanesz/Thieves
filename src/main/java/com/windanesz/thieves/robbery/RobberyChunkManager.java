package com.windanesz.thieves.robbery;

import com.windanesz.thieves.Thieves;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages one ForgeChunkManager ticket <em>per player</em> so that multiple
 * simultaneous robberies (e.g. on a server with several online players) each
 * keep their own base chunks loaded independently.
 *
 * <p>Previously a single static ticket was used; starting a second robbery
 * silently released the first player's ticket, unloading their base chunks
 * and causing their thieves to despawn.</p>
 */
public class RobberyChunkManager {

    /** player UUID → active chunk ticket for their robbery */
    private static final Map<UUID, ForgeChunkManager.Ticket> tickets = new HashMap<>();

    /** player UUID → set of chunks currently force-loaded for their robbery */
    private static final Map<UUID, Set<ChunkPos>> forcedChunkSets = new HashMap<>();

    /**
     * Requests a chunk-loading ticket for {@code playerUUID} and force-loads a
     * 7×7 chunk area around the base location.  Call this when a robbery starts.
     * If the player already has an active ticket (e.g., a previous robbery
     * didn't clean up), the old one is released first.
     */
    public static void startRobbery(UUID playerUUID, World world, BlockPos basePos) {
        if (world.isRemote) return;

        // Release any leftover ticket for this player from a previous robbery
        endRobbery(playerUUID);

        ForgeChunkManager.Ticket ticket =
                ForgeChunkManager.requestTicket(Thieves.instance, world, ForgeChunkManager.Type.NORMAL);

        if (ticket == null) {
            Thieves.LOGGER.warn(
                    "RobberyChunkManager: Could not obtain a chunk ticket for player {}. " +
                    "Thieves may despawn if the player moves far from their base.", playerUUID);
            return;
        }

        Set<ChunkPos> loaded = new HashSet<>();
        ChunkPos center = new ChunkPos(basePos);
        int radius = 3; // 7×7 chunk area

        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                ChunkPos cp = new ChunkPos(x, z);
                ForgeChunkManager.forceChunk(ticket, cp);
                loaded.add(cp);
            }
        }

        tickets.put(playerUUID, ticket);
        forcedChunkSets.put(playerUUID, loaded);

        Thieves.LOGGER.debug(
                "RobberyChunkManager: Force-loaded {} chunks around {} for player {}.",
                loaded.size(), basePos, playerUUID);
    }

    /**
     * Releases the chunk ticket for this specific player's robbery.
     * Safe to call even if no ticket exists for this player.
     */
    public static void endRobbery(UUID playerUUID) {
        ForgeChunkManager.Ticket ticket = tickets.remove(playerUUID);
        if (ticket == null) return;

        Set<ChunkPos> loaded = forcedChunkSets.remove(playerUUID);
        if (loaded != null) {
            for (ChunkPos cp : loaded) {
                ForgeChunkManager.unforceChunk(ticket, cp);
            }
        }
        ForgeChunkManager.releaseTicket(ticket);

        Thieves.LOGGER.debug("RobberyChunkManager: Released chunk ticket for player {}.", playerUUID);
    }

    /**
     * Releases ALL active robbery tickets for a specific world (e.g., on world unload).
     * Does not despawn any thieves — entity serialization handles that.
     */
    public static void releaseAllForWorld(World world) {
        java.util.Iterator<Map.Entry<UUID, ForgeChunkManager.Ticket>> it = tickets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ForgeChunkManager.Ticket> entry = it.next();
            if (entry.getValue().world != world) continue;

            Set<ChunkPos> loaded = forcedChunkSets.remove(entry.getKey());
            if (loaded != null) {
                for (ChunkPos cp : loaded) {
                    ForgeChunkManager.unforceChunk(entry.getValue(), cp);
                }
            }
            ForgeChunkManager.releaseTicket(entry.getValue());
            Thieves.LOGGER.debug(
                    "RobberyChunkManager: Released chunk ticket for player {} during world unload.",
                    entry.getKey());
            it.remove();
        }
    }

    /**
     * Returns whether a chunk ticket is currently active for this player.
     */
    public static boolean isActive(UUID playerUUID) {
        return tickets.containsKey(playerUUID);
    }
}
