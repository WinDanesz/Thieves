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

public class CommandGetPlayerBase extends CommandBase {

	@Override
	public String getName() {
		return "getplayerbase";
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return "/getplayerbase [player] - Shows player base information";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
		if (args.length > 1) {
			throw new WrongUsageException(getUsage(sender));
		}

		EntityPlayerMP player = args.length == 1 ? getPlayer(server, sender, args[0]) : getCommandSenderAsPlayer(sender);

		PlayerCapability cap = PlayerCapability.get(player);
		if (cap == null) {
			throw new CommandException("Could not get capability for player " + player.getName());
		}

		BlockPos basePos = cap.baseLocation;
		
		if (basePos == null) {
			sender.sendMessage(new TextComponentString(
					TextFormatting.YELLOW + "No base detected for " + player.getName()));
			return;
		}

		// Display base information
		sender.sendMessage(new TextComponentString(
				TextFormatting.GOLD + "========== Player Base Info: " + player.getName() + " =========="));
		
		sender.sendMessage(new TextComponentString(
				TextFormatting.AQUA + "Location: " + TextFormatting.WHITE +
						basePos.getX() + ", " + basePos.getY() + ", " + basePos.getZ()));
		
		sender.sendMessage(new TextComponentString(
				TextFormatting.AQUA + "Dimension: " + TextFormatting.WHITE + cap.baseDimension));
		
		sender.sendMessage(new TextComponentString(
				TextFormatting.AQUA + "Type: " + TextFormatting.WHITE +
						(cap.isManuallySetBase() ? "Manually Set" : "Auto-Detected")));
		
		sender.sendMessage(new TextComponentString(
				TextFormatting.AQUA + "Chests: " + TextFormatting.WHITE + cap.chestCountAtBase));
		
		sender.sendMessage(new TextComponentString(
				TextFormatting.AQUA + "Total Value: " + TextFormatting.WHITE + String.format("%.2f", cap.chestValueScore)));
		
		if (cap.lastBaseDetectionTime > 0) {
			long timeSince = (System.currentTimeMillis() - cap.lastBaseDetectionTime) / 1000;
			String timeStr;
			if (timeSince < 60) {
				timeStr = timeSince + " seconds ago";
			} else if (timeSince < 3600) {
				timeStr = (timeSince / 60) + " minutes ago";
			} else {
				timeStr = (timeSince / 3600) + " hours ago";
			}
			sender.sendMessage(new TextComponentString(
					TextFormatting.AQUA + "Last Updated: " + TextFormatting.WHITE + timeStr));
		}
		
		sender.sendMessage(new TextComponentString(
				TextFormatting.GOLD + "================================================"));
	}

	@Override
	public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
		if (args.length == 1) {
			return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
		}
		return Collections.emptyList();
	}
}
