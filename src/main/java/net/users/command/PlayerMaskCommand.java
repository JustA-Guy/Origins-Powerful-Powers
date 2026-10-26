package net.users.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.users.tracking.PlayerMaskTracker;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Commands for PlayerMaskTracker:
 *   /opp obfuscate <targets> [duration <ticks>] [viewers <viewers>]     names show as magic text
 *   /opp unobfuscate <targets>
 *   /opp tablist hide <targets> [duration <ticks>] [viewers <viewers>]  left out of the tab list
 *   /opp tablist show <targets>
 * The optional parts are keywords, given in any order (CommandOptions). The viewers are fixed by UUID when the
 * command runs; left out, every other player. hide and show share their rule
 * with the hide_from_tablist and show_in_tablist actions. Powers aren't affected by any of these.
 */
public final class PlayerMaskCommand {
	/** duration and viewers, after the targets. */
	private static final List<CommandOptions.Option> OPTIONS = List.of(
		CommandOptions.option("duration", IntegerArgumentType.integer(0)),
		CommandOptions.option("viewers", EntityArgument.players()));

	private PlayerMaskCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("opp")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("obfuscate")
				.then(CommandOptions.with(Commands.argument("targets", EntityArgument.players()), OPTIONS,
					context -> start(context, PlayerMaskTracker.Kind.OBFUSCATE))))
			.then(Commands.literal("unobfuscate")
				.then(Commands.argument("targets", EntityArgument.players())
					.executes(context -> stop(context, PlayerMaskTracker.Kind.OBFUSCATE))))
			.then(Commands.literal("tablist")
				.then(Commands.literal("hide")
					.then(CommandOptions.with(Commands.argument("targets", EntityArgument.players()), OPTIONS,
						context -> start(context, PlayerMaskTracker.Kind.TAB_HIDE))))
				.then(Commands.literal("show")
					.then(Commands.argument("targets", EntityArgument.players())
						.executes(context -> stop(context, PlayerMaskTracker.Kind.TAB_HIDE)))))
		);
	}

	/** Returns how many players it applied to. */
	private static int start(CommandContext<CommandSourceStack> context, PlayerMaskTracker.Kind kind) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
		int duration = CommandOptions.has(context, "duration") ? IntegerArgumentType.getInteger(context, "duration") : 0;
		Set<UUID> viewers = null;
		if (CommandOptions.has(context, "viewers")) {
			viewers = new HashSet<>();
			for (ServerPlayer viewer : EntityArgument.getPlayers(context, "viewers")) {
				viewers.add(viewer.getUUID());
			}
		}
		Set<UUID> chosen = viewers;
		PlayerMaskTracker.ViewerTest test = (viewer, holder) -> chosen == null || chosen.contains(viewer.getUUID());
		for (ServerPlayer target : targets) {
			PlayerMaskTracker.put(kind, target.getUUID(), PlayerMaskTracker.MANUAL, test, duration);
		}
		String what = kind == PlayerMaskTracker.Kind.OBFUSCATE ? "Obfuscated the name of " : "Hid from the tab list: ";
		String who = targets.size() == 1 ? targets.iterator().next().getGameProfile().getName() : targets.size() + " players";
		String forWhom = chosen == null ? "" : " for " + chosen.size() + " viewer(s)";
		String howLong = duration > 0 ? " for " + duration + " ticks" : "";
		context.getSource().sendSuccess(() -> Component.literal(what + who + forWhom + howLong), true);
		return targets.size();
	}

	/** Returns how many of the targets had a command or action rule to remove. */
	private static int stop(CommandContext<CommandSourceStack> context, PlayerMaskTracker.Kind kind) throws CommandSyntaxException {
		int stopped = 0;
		for (ServerPlayer target : EntityArgument.getPlayers(context, "targets")) {
			if (PlayerMaskTracker.remove(kind, target.getUUID(), PlayerMaskTracker.MANUAL)) {
				stopped++;
			}
		}
		int count = stopped;
		String what = kind == PlayerMaskTracker.Kind.OBFUSCATE ? "Stopped obfuscating " : "Showing in the tab list again: ";
		context.getSource().sendSuccess(() -> Component.literal(what + count + " player(s)"), true);
		return count;
	}
}
