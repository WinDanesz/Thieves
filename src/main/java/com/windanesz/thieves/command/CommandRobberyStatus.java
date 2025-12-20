package com.windanesz.thieves.command;

import com.windanesz.thieves.Settings;
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class CommandRobberyStatus extends CommandBase {

	@Nonnull
	@Override
	public String getName() {
		return "robberystatus";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender) {
		return "/robberystatus [player]";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 0;
	}

	@Override
	public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args) throws CommandException {
		EntityPlayerMP player;

		if (args.length == 0) {
			if (!(sender instanceof EntityPlayerMP)) {
				throw new WrongUsageException(getUsage(sender));
			}
			player = (EntityPlayerMP) sender;
		} else {
			if (!sender.canUseCommand(2, getName())) {
				throw new CommandException("You don't have permission to check other players");
			}
			player = getPlayer(server, sender, args[0]);
		}

		PlayerCapability cap = PlayerCapability.get(player);
		if (cap == null) {
			throw new CommandException("Could not get player data");
		}

		if (cap.baseLocation == null) {
			sender.sendMessage(new TextComponentString("No base detected. Sleep in a bed to set one."));
			return;
		}

		// Progress
		float progress = cap.robberyProgress;
		sender.sendMessage(new TextComponentString(
				TextFormatting.AQUA + "Robbery Progress: " + TextFormatting.WHITE + 
				String.format("%.1f%%", progress)));

		// Status
		if (progress >= 100.0F) {
			long timeSinceLastRobbery = player.world.getTotalWorldTime() - cap.lastRobberyTime;
			long cooldownTicks = (long) (Settings.robbery.minCooldownDays * 24000);

			if (timeSinceLastRobbery < cooldownTicks) {
				sender.sendMessage(new TextComponentString(
						TextFormatting.GREEN + "Cooldown: " + formatTime(cooldownTicks - timeSinceLastRobbery)));
			} else {
				if (Settings.robbery.nightOnly) {
					long dayTime = player.world.getWorldTime() % 24000;
					if (dayTime >= 12542 && dayTime <= 23458) {
						sender.sendMessage(new TextComponentString(
								TextFormatting.RED + "ROBBERY IMMINENT!"));
					} else {
						long ticksUntilNight = (24000 - dayTime + 12542) % 24000;
						sender.sendMessage(new TextComponentString(
								TextFormatting.RED + "Robbery at nightfall: " + formatTime(ticksUntilNight)));
					}
				} else {
					sender.sendMessage(new TextComponentString(
							TextFormatting.RED + "ROBBERY CAN HAPPEN NOW!"));
				}
			}
		} else {
			// Estimate time
			float progressRate = calculateProgressRate(cap);
			if (progressRate > 0.001F) {
				float minutesRemaining = (100.0F - progress) / progressRate;
				long ticksRemaining = (long) (minutesRemaining * 1200.0F);
				sender.sendMessage(new TextComponentString(
						"Time until 100%: " + formatTime(ticksRemaining)));
			}
		}

		// Base info
		sender.sendMessage(new TextComponentString(
				TextFormatting.GRAY + "Base value: " + String.format("%.0f", cap.chestValueScore) + 
				" (" + cap.chestCountAtBase + " chests)"));
	}

	private float calculateProgressRate(PlayerCapability cap) {
		if (cap.chestValueScore <= 0) return 0.0F;
		float baseRate = Settings.robbery.baseProgressRate;
		float maxRate = Settings.robbery.maxProgressRate;
		float valueFactor = Math.min(cap.chestValueScore / 1000.0F, 1.0F);
		return baseRate + (maxRate - baseRate) * valueFactor;
	}

	private String formatTime(long ticks) {
		long seconds = ticks / 20;
		long minutes = seconds / 60;
		long hours = minutes / 60;
		long days = hours / 24;

		if (days > 0) return days + "d " + (hours % 24) + "h";
		if (hours > 0) return hours + "h " + (minutes % 60) + "m";
		if (minutes > 0) return minutes + "m";
		return seconds + "s";
	}

	@Nonnull
	@Override
	public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args, @Nullable BlockPos targetPos) {
		if (args.length == 1 && sender.canUseCommand(2, getName())) {
			return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
		}
		return Collections.emptyList();
	}
}
