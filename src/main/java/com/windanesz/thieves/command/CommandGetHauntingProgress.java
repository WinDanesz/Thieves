package com.windanesz.thieves.command;

import com.windanesz.thieves.capability.PlayerCapability;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class CommandGetHauntingProgress extends CommandBase {

	@Nonnull
	@Override
	public String getName() {
		return "gethauntingprogress";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender) {
		return "commands.thieves.gethauntingprogress.usage";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args) throws CommandException {
		if (args.length != 1) {
			throw new WrongUsageException(getUsage(sender));
		}

		EntityPlayerMP player = getPlayer(server, sender, args[0]);

		PlayerCapability haunting = PlayerCapability.get(player);
		if (haunting != null) {
			int progress = haunting.getHauntingProgress();
			notifyCommandListener(sender, this, "commands.thieves.gethauntingprogress.success", player.getName(), progress);
		} else {
			throw new CommandException("commands.thieves.gethauntingprogress.error", player.getName());
		}
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