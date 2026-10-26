package net.users.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.users.variables.WithVariables;

import java.util.ArrayList;
import java.util.List;

/**
 * /opp with <targets> <command>: fills ${expression} in the command from each target's variables and
 * lists, then runs it as that target, like execute as. This is how chat, command
 * blocks and functions use variables; inside powers, origins-powerful-powers:with_variables does the same.
 *
 * Every target is filled in before anything runs, so a bad placeholder fails the whole command.
 * Returns the sum of the commands' results, for execute store.
 */
public final class WithCommand {
	private static final Dynamic2CommandExceptionType FILL_FAILED = new Dynamic2CommandExceptionType(
		(entity, message) -> Component.literal("Couldn't fill in the command for " + entity + ": " + message));

	private WithCommand() {
	}

	private record Filled(Entity target, String command) {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("opp")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("with")
				.then(Commands.argument("targets", EntityArgument.entities())
					.then(Commands.argument("command", StringArgumentType.greedyString())
						.executes(WithCommand::run))))
		);
	}

	private static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String command = StringArgumentType.getString(context, "command");
		List<Filled> filled = new ArrayList<>();
		for (Entity target : EntityArgument.getEntities(context, "targets")) {
			try {
				filled.add(new Filled(target, WithVariables.fillCommand(command, target)));
			} catch (RuntimeException e) {
				throw FILL_FAILED.create(target.getDisplayName().getString(), e.getMessage());
			}
		}
		CommandSourceStack source = context.getSource();
		int total = 0;
		for (Filled each : filled) {
			total += source.getServer().getCommands().performPrefixedCommand(source.withEntity(each.target()), each.command());
		}
		return total;
	}
}
