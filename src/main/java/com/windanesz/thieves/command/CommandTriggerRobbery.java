package com.windanesz.thieves.command;

import com.windanesz.thieves.capability.PlayerCapability;
import com.windanesz.thieves.robbery.RobberySpawner;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class CommandTriggerRobbery extends CommandBase {

	@Nonnull
	@Override
	public String getName() {
		return "triggerrobbery";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender) {
		return "/triggerrobbery [player]";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args) throws CommandException {
		EntityPlayerMP player;
		
		if (args.length == 0) {
			// No player specified, use command sender if it's a player
			if (sender instanceof EntityPlayerMP) {
				player = (EntityPlayerMP) sender;
			} else {
				throw new WrongUsageException(getUsage(sender));
			}
		} else {
			// Player specified
			player = getPlayer(server, sender, args[0]);
		}

		PlayerCapability cap = PlayerCapability.get(player);
		if (cap == null) {
			throw new CommandException("Could not get capability for player " + player.getName());
		}

		// Check if player has a detected base
		if (cap.baseLocation == null) {
			sender.sendMessage(new TextComponentString(TextFormatting.RED + "Player " + player.getName() + " has no detected base. Cannot trigger robbery."));
			return;
		}

		// Trigger the robbery
		RobberySpawner.triggerRobbery(player, cap);
		sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Robbery triggered for player " + player.getName()));
	}

	@Nonnull
	@Override
	public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args, @Nullable BlockPos targetPos) {
		if (args.length == 1) {
			return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
		}
		return Collections.emptyList();
	}
}
