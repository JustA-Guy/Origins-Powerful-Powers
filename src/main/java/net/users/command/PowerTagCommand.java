package net.users.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.users.power.PowerTags;

import java.util.Collection;
import java.util.List;

/**
 * /opp powertag: what the tag files resolved to after loading.
 *   list            every tag and how many powers it covers
 *   list <tag>      the powers in one tag, and the entries it was written with
 *   of <power>      the tags on one power
 */
public final class PowerTagCommand {
	private static final DynamicCommandExceptionType UNKNOWN_TAG = new DynamicCommandExceptionType(
		tag -> Component.literal("No power tag " + tag + " is defined in any data/<namespace>/opp_power_tags.json"));
	private static final SuggestionProvider<CommandSourceStack> TAGS = (context, builder) ->
		SharedSuggestionProvider.suggest(PowerTags.tags(), builder);

	private PowerTagCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("opp")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("powertag")
				.then(Commands.literal("list")
					.executes(PowerTagCommand::listAll)
					.then(Commands.argument("tag", StringArgumentType.string()).suggests(TAGS).executes(PowerTagCommand::listOne)))
				.then(Commands.literal("of")
					.then(Commands.argument("power", net.minecraft.commands.arguments.ResourceLocationArgument.id()).executes(PowerTagCommand::of))))
		);
	}

	private static int listAll(CommandContext<CommandSourceStack> context) {
		Collection<String> tags = PowerTags.tags();
		context.getSource().sendSuccess(() -> Component.literal(tags.size() + " power tag" + (tags.size() == 1 ? "" : "s")), false);
		for (String tag : tags) {
			int count = PowerTags.powersIn(tag).size();
			context.getSource().sendSuccess(() -> Component.literal("  " + tag + ": " + count + " power" + (count == 1 ? "" : "s")), false);
		}
		return tags.size();
	}

	private static int listOne(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String tag = StringArgumentType.getString(context, "tag");
		if (!PowerTags.exists(tag)) {
			throw UNKNOWN_TAG.create(tag);
		}
		List<String> powers = PowerTags.powersIn(tag);
		context.getSource().sendSuccess(() -> Component.literal(tag + ": " + powers.size() + " power" + (powers.size() == 1 ? "" : "s")
			+ ", written as " + String.join(", ", PowerTags.entriesOf(tag))), false);
		for (String power : powers) {
			context.getSource().sendSuccess(() -> Component.literal("  " + power), false);
		}
		return powers.size();
	}

	private static int of(CommandContext<CommandSourceStack> context) {
		ResourceLocation power = net.minecraft.commands.arguments.ResourceLocationArgument.getId(context, "power");
		Collection<String> tags = PowerTags.tagsOf(power);
		context.getSource().sendSuccess(() -> Component.literal(tags.isEmpty() ? power + " has no tags"
			: power + ": " + String.join(", ", tags)), false);
		return tags.size();
	}
}
