package net.users.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.users.tracking.FakeBlockTracker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /opp fakeblock:
 *   set <pos> <block> <viewers> [duration <ticks>]   show those players another block at pos
 *   clear <pos>                                stop showing whatever was there
 * The viewers are fixed when the command runs, by UUID, so they still see it after relogging.
 */
public final class FakeBlockCommand {
	private FakeBlockCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("opp")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("fakeblock")
				.then(Commands.literal("set")
					.then(Commands.argument("pos", BlockPosArgument.blockPos())
						.then(Commands.argument("block", BlockStateArgument.block(buildContext))
							.then(CommandOptions.with(Commands.argument("viewers", EntityArgument.players()),
								List.of(CommandOptions.option("duration", IntegerArgumentType.integer(0))), context -> set(context,
									CommandOptions.has(context, "duration") ? IntegerArgumentType.getInteger(context, "duration") : 0))))))
				.then(Commands.literal("clear")
					.then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(FakeBlockCommand::clear))))
		);
	}

	private static int set(CommandContext<CommandSourceStack> context, int duration) throws CommandSyntaxException {
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
		BlockState state = BlockStateArgument.getBlock(context, "block").getState();
		Set<UUID> viewers = new HashSet<>();
		for (ServerPlayer player : EntityArgument.getPlayers(context, "viewers")) {
			viewers.add(player.getUUID());
		}
		ServerLevel level = context.getSource().getLevel();
		FakeBlockTracker.place(level, pos, state, null, null, null, viewers, false, duration);
		int count = viewers.size();
		context.getSource().sendSuccess(() -> Component.literal("Showing " + state.getBlock().getName().getString() + " at "
			+ pos.toShortString() + " to " + count + " player" + (count == 1 ? "" : "s")), true);
		return count;
	}

	private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
		int removed = FakeBlockTracker.clear(context.getSource().getLevel(), pos);
		context.getSource().sendSuccess(() -> Component.literal(removed == 0 ? "No fake block at " + pos.toShortString()
			: "Cleared the fake block at " + pos.toShortString()), true);
		return removed;
	}
}
