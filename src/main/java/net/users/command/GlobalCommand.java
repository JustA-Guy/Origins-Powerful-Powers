package net.users.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.users.variables.GlobalStore;
import net.users.variables.ScriptException;

import java.util.ArrayList;
import java.util.List;

/**
 * /opp global: look at and change the global store. Commands are never
 * blocked by locks.
 *   get <key>, set <key> <value>, delete <key>, list [<prefix>], prune <prefix>, prune --older-than <ticks>,
 *   lock <key> <owner>, unlock <key>
 */
public final class GlobalCommand {
	private static final DynamicCommandExceptionType FAILED = new DynamicCommandExceptionType(message -> Component.literal(String.valueOf(message)));
	private static final SuggestionProvider<CommandSourceStack> KEYS = (context, builder) ->
		SharedSuggestionProvider.suggest(GlobalStore.get(context.getSource().getServer()).entries().stream().map(entry -> keyOf(entry)), builder);

	private GlobalCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("opp")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("global")
				.then(Commands.literal("get").then(key().executes(GlobalCommand::get)))
				.then(Commands.literal("set").then(key()
					.then(Commands.argument("value", StringArgumentType.greedyString()).executes(GlobalCommand::set))))
				.then(Commands.literal("delete").then(key().executes(GlobalCommand::delete)))
				.then(Commands.literal("list")
					.executes(context -> list(context, null))
					.then(Commands.argument("prefix", ResourceLocationArgument.id()).executes(context -> list(context, raw(context, "prefix")))))
				.then(Commands.literal("prune")
					.then(Commands.literal("--older-than").then(Commands.argument("ticks", LongArgumentType.longArg(0))
						.executes(context -> prune(context, null, LongArgumentType.getLong(context, "ticks")))))
					.then(Commands.argument("prefix", ResourceLocationArgument.id())
						.executes(context -> prune(context, raw(context, "prefix"), 0))))
				.then(Commands.literal("lock").then(key()
					.then(Commands.argument("owner", ResourceLocationArgument.id()).executes(GlobalCommand::lock))))
				.then(Commands.literal("unlock").then(key().executes(GlobalCommand::unlock))))
		);
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, net.minecraft.resources.ResourceLocation> key() {
		return Commands.argument("key", ResourceLocationArgument.id()).suggests(KEYS);
	}

	private static GlobalStore store(CommandContext<CommandSourceStack> context) {
		return GlobalStore.get(context.getSource().getServer());
	}

	private static String keyArg(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			return GlobalStore.normalize(raw(context, "key"));
		} catch (ScriptException e) {
			throw FAILED.create(e.getMessage());
		}
	}

	/** Prints the value; returns it for execute store when it's a whole number or boolean, else 1. */
	private static int get(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String key = keyArg(context);
		GlobalStore.Entry entry = store(context).entry(key);
		if (entry == null) {
			throw FAILED.create("There's no global " + key);
		}
		context.getSource().sendSuccess(() -> Component.literal(key + " = " + entry.describe()), false);
		if (entry.isList()) {
			return store(context).listForCommand(key).size();
		}
		Object value = entry.value();
		if (value instanceof Number number) {
			return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number.longValue()));
		}
		return value instanceof Boolean flag ? (flag ? 1 : 0) : 1;
	}

	private static int set(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String key = keyArg(context);
		try {
			Object value = store(context).set(key, GlobalStore.literal(StringArgumentType.getString(context, "value")), null);
			context.getSource().sendSuccess(() -> Component.literal("Set global " + key + " to " + store(context).entry(key).describe()), true);
			return value instanceof Number number ? number.intValue() : 1;
		} catch (ScriptException e) {
			throw FAILED.create(e.getMessage());
		}
	}

	private static int delete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String key = keyArg(context);
		if (!store(context).delete(key, null)) {
			throw FAILED.create("There's no global " + key);
		}
		context.getSource().sendSuccess(() -> Component.literal("Deleted global " + key), true);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> context, String prefix) {
		GlobalStore store = store(context);
		String full = prefix == null ? null : prefix.indexOf(':') < 0 ? "origins-powerful-powers:" + prefix : prefix;
		long now = store.gameTime();
		List<String> lines = new ArrayList<>();
		for (GlobalStore.Entry entry : store.entries()) {
			String key = keyOf(entry);
			if (full != null && !key.startsWith(full)) {
				continue;
			}
			String type = entry.isList() ? "list of " + entry.type().keyword() + " (" + store.listForCommand(key).size() + ")" : entry.type().keyword();
			lines.add(key + " [" + type + "] = " + shorten(entry.describe()) + ", written " + (now - entry.lastWritten()) + " ticks ago"
				+ (entry.owner() == null ? "" : ", locked to " + entry.owner())
				+ (entry.bound().isEmpty() ? "" : ", bound to " + entry.bound().size() + " entit" + (entry.bound().size() == 1 ? "y" : "ies")));
		}
		int count = lines.size();
		context.getSource().sendSuccess(() -> Component.literal(count + " global" + (count == 1 ? "" : "s") + (full == null ? "" : " starting with " + full)), false);
		for (String line : lines) {
			context.getSource().sendSuccess(() -> Component.literal("  " + line), false);
		}
		return count;
	}

	private static int prune(CommandContext<CommandSourceStack> context, String prefix, long olderThan) {
		int removed = store(context).prune(prefix, olderThan);
		context.getSource().sendSuccess(() -> Component.literal("Deleted " + removed + " global" + (removed == 1 ? "" : "s")), true);
		return removed;
	}

	private static int lock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String key = keyArg(context);
		String owner = raw(context, "owner");
		try {
			store(context).lock(key, owner, null);
		} catch (ScriptException e) {
			throw FAILED.create(e.getMessage());
		}
		context.getSource().sendSuccess(() -> Component.literal("Locked global " + key + " to " + owner), true);
		return 1;
	}

	private static int unlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String key = keyArg(context);
		try {
			store(context).unlock(key, null);
		} catch (ScriptException e) {
			throw FAILED.create(e.getMessage());
		}
		context.getSource().sendSuccess(() -> Component.literal("Unlocked global " + key), true);
		return 1;
	}

	/**
	 * The argument exactly as typed. ResourceLocationArgument checks the characters, but it would read a key without a
	 * namespace as minecraft:, while the store defaults to origins-powerful-powers:.
	 */
	private static String raw(CommandContext<CommandSourceStack> context, String name) {
		for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
			if (node.getNode().getName().equals(name)) {
				return node.getRange().get(context.getInput());
			}
		}
		throw new IllegalArgumentException("no argument " + name);
	}

	private static String keyOf(GlobalStore.Entry entry) {
		return entry.key();
	}

	private static String shorten(String text) {
		return text.length() <= 60 ? text : text.substring(0, 57) + "...";
	}
}
