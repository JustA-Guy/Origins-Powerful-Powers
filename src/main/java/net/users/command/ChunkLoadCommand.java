package net.users.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.users.tracking.ChunkLoadTracker;

import java.util.Collection;
import java.util.List;

/**
 * /opp chunkload:
 *   start <targets> [radius <n>] [duration <ticks>]   the same tie as the load_chunks action; running it again replaces it
 *   stop <targets>                            the same as stop_loading_chunks; a chunk_loader power keeps going
 *   list                                      every entity loading chunks, and why
 */
public final class ChunkLoadCommand {
	/** radius and duration, after the targets. */
	private static final List<CommandOptions.Option> OPTIONS = List.of(
		CommandOptions.option("radius", IntegerArgumentType.integer(0)),
		CommandOptions.option("duration", IntegerArgumentType.integer(0)));

	private ChunkLoadCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("opp")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("chunkload")
				.then(Commands.literal("start")
					.then(CommandOptions.with(Commands.argument("targets", EntityArgument.entities()), OPTIONS, context -> start(context,
						CommandOptions.has(context, "radius") ? IntegerArgumentType.getInteger(context, "radius") : 1,
						CommandOptions.has(context, "duration") ? IntegerArgumentType.getInteger(context, "duration") : 0))))
				.then(Commands.literal("stop")
					.then(Commands.argument("targets", EntityArgument.entities()).executes(ChunkLoadCommand::stop)))
				.then(Commands.literal("list").executes(ChunkLoadCommand::list)))
		);
	}

	/** Returns how many entities it started on. */
	private static int start(CommandContext<CommandSourceStack> context, int radius, int duration) throws CommandSyntaxException {
		Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
		for (Entity entity : targets) {
			ChunkLoadTracker.start(entity, ChunkLoadTracker.ACTION_SOURCE, radius, duration);
		}
		int size = 2 * radius + 1;
		String who = targets.size() == 1 ? targets.iterator().next().getName().getString() : targets.size() + " entities";
		context.getSource().sendSuccess(() -> Component.literal("Loading " + size + "x" + size + " chunks around " + who
			+ (duration > 0 ? " for " + duration + " ticks" : " until stopped")), true);
		return targets.size();
	}

	/** Returns how many of the targets had a command or action tie to stop. */
	private static int stop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		int stopped = 0;
		for (Entity entity : EntityArgument.getEntities(context, "targets")) {
			if (ChunkLoadTracker.stop(entity.getUUID(), ChunkLoadTracker.ACTION_SOURCE)) {
				stopped++;
			}
		}
		int count = stopped;
		context.getSource().sendSuccess(() -> Component.literal("Stopped chunk loading on " + count + " entit" + (count == 1 ? "y" : "ies")), true);
		return count;
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		List<String> lines = ChunkLoadTracker.describeAll();
		if (lines.isEmpty()) {
			context.getSource().sendSuccess(() -> Component.literal("No entities are loading chunks"), false);
		}
		for (String line : lines) {
			context.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		return lines.size();
	}
}
