package com.windanesz.thieves.command;

import com.windanesz.thieves.capability.PlayerCapability;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class CommandSetPlayerBase extends CommandBase {

	@Override
	public String getName() {
		return "setplayerbase";
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return "/setplayerbase [player] - Sets player base at current location\n" +
				"/setplayerbase [player] <x> <y> <z> - Sets player base at specific coordinates\n" +
				"/setplayerbase [player] reset - Clears manual base and re-enables auto-detection";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
		if (args.length < 1) {
			throw new WrongUsageException(getUsage(sender));
		}

		EntityPlayerMP player = args.length >= 1 ? getPlayer(server, sender, args[0]) : getCommandSenderAsPlayer(sender);

		PlayerCapability cap = PlayerCapability.get(player);
		if (cap == null) {
			throw new CommandException("Could not get capability for player " + player.getName());
		}

		// Handle reset command
		if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
			cap.clearManualBase();
			sender.sendMessage(new TextComponentString(
					TextFormatting.GREEN + "Cleared manual base for " + player.getName() + ". Auto-detection re-enabled."));
			return;
		}

		BlockPos basePos;
		int dimension = player.dimension;

		// Set base at specific coordinates
		if (args.length == 4) {
			try {
				int x = parseInt(args[1]);
				int y = parseInt(args[2]);
				int z = parseInt(args[3]);
				basePos = new BlockPos(x, y, z);
			} catch (NumberFormatException e) {
				throw new WrongUsageException(getUsage(sender));
			}
		}
		// Set base at player's current location
		else if (args.length == 1) {
			basePos = player.getPosition();
		} else {
			throw new WrongUsageException(getUsage(sender));
		}

		cap.setManualBase(basePos, dimension);

		sender.sendMessage(new TextComponentString(
				TextFormatting.GREEN + "Set base for " + player.getName() + " to: " +
						TextFormatting.GOLD + basePos.getX() + ", " + basePos.getY() + ", " + basePos.getZ() +
						TextFormatting.GREEN + " in dimension " + TextFormatting.GOLD + dimension));
	}

	@Override
	public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
		if (args.length == 1) {
			return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
		}
		if (args.length == 2) {
			return getListOfStringsMatchingLastWord(args, "reset", "~");
		}
		if (args.length == 3 || args.length == 4) {
			return getListOfStringsMatchingLastWord(args, "~");
		}
		return Collections.emptyList();
	}
}
