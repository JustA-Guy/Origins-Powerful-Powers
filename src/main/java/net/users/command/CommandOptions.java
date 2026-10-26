package net.users.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional command arguments written as a keyword and a value, in any order: "duration 600 viewers @a" and
 * "viewers @a duration 600" are the same. Each keyword can be given once.
 */
final class CommandOptions {
	/** One optional argument: its keyword, which is also its argument name, and its value type. */
	record Option(String name, ArgumentType<?> type, SuggestionProvider<CommandSourceStack> suggestions) {
	}

	private CommandOptions() {
	}

	static Option option(String name, ArgumentType<?> type) {
		return new Option(name, type, null);
	}

	static Option option(String name, ArgumentType<?> type, SuggestionProvider<CommandSourceStack> suggestions) {
		return new Option(name, type, suggestions);
	}

	/** Makes the node run the command, then adds every option under it, each followed by the ones not yet given. */
	static <T extends ArgumentBuilder<CommandSourceStack, T>> T with(T node, List<Option> options, Command<CommandSourceStack> command) {
		node.executes(command);
		for (Option option : options) {
			List<Option> rest = new ArrayList<>(options);
			rest.remove(option);
			RequiredArgumentBuilder<CommandSourceStack, ?> value = Commands.argument(option.name(), option.type());
			if (option.suggestions() != null) {
				value.suggests(option.suggestions());
			}
			node.then(Commands.literal(option.name()).then(with(value, rest, command)));
		}
		return node;
	}

	/** Whether the option was given. */
	static boolean has(CommandContext<CommandSourceStack> context, String name) {
		try {
			context.getArgument(name, Object.class);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
