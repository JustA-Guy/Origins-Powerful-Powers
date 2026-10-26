package net.users.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.users.tracking.DisguiseTracker;
import net.users.tracking.FakeEntityTracker;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Commands for the spawn_fake_entity and disguise actions:
 *   /opp fakeentity spawn <owner> <entity_type> <pos> <lifetime> [behavior <behavior>] [viewers <viewers>] [id <id>]
 *   /opp fakeentity player <owner> <username> <pos> <lifetime> [behavior <behavior>] [viewers <viewers>] [id <id>]
 * spawn and player return the fake entity's id (assigned unless one is given), so execute store can keep it for remove.
 *   /opp fakeentity remove <id> | remove-all <owner> | list
 *   /opp fakeentity run <id> <command>   (runs the command as the fake entity: @s is the fake entity)
 *   /opp disguise <targets> <entity_type> [duration <ticks>] [viewers <viewers>]
 *   /opp undisguise <targets>
 * The optional parts are keywords, given in any order (CommandOptions). A viewers selector is resolved once, when the
 * command runs: only those players can see the result, like viewer_condition with recheck off. Without one, every
 * player can.
 */
public final class IllusionCommand {
	private static final DynamicCommandExceptionType BAD_TYPE = new DynamicCommandExceptionType(
		type -> Component.literal(type + " can't be a fake entity or disguise (players and types that can't be summoned aren't allowed)"));

	private static final DynamicCommandExceptionType NO_FAKE_ENTITY = new DynamicCommandExceptionType(
		id -> Component.literal("There's no fake entity " + id));

	private static final DynamicCommandExceptionType BAD_BEHAVIOR = new DynamicCommandExceptionType(
		name -> Component.literal("Unknown behavior " + name + ": use hostile, neutral, passive or stationary"));

	/** behavior, viewers and id, after a fake entity's lifetime. */
	private static final List<CommandOptions.Option> SPAWN_OPTIONS = List.of(
		CommandOptions.option("behavior", StringArgumentType.word(), (context, builder) -> SharedSuggestionProvider.suggest(
			Arrays.stream(FakeEntityTracker.Behavior.values()).map(b -> b.name().toLowerCase(Locale.ROOT)), builder)),
		CommandOptions.option("viewers", EntityArgument.players()),
		CommandOptions.option("id", IntegerArgumentType.integer(1)));

	/** duration and viewers, after a disguise's entity type. */
	private static final List<CommandOptions.Option> DISGUISE_OPTIONS = List.of(
		CommandOptions.option("duration", IntegerArgumentType.integer(0)),
		CommandOptions.option("viewers", EntityArgument.players()));

	private IllusionCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("opp")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("fakeentity")
				.then(Commands.literal("spawn")
					.then(Commands.argument("owner", EntityArgument.entity())
						.then(entityType(buildContext)
							.then(Commands.argument("pos", Vec3Argument.vec3())
								.then(CommandOptions.with(Commands.argument("lifetime", IntegerArgumentType.integer(1)), SPAWN_OPTIONS,
									context -> spawnFakeEntity(context, false)))))))
				.then(Commands.literal("player")
					.then(Commands.argument("owner", EntityArgument.entity())
						.then(Commands.argument("username", StringArgumentType.word())
							.then(Commands.argument("pos", Vec3Argument.vec3())
								.then(CommandOptions.with(Commands.argument("lifetime", IntegerArgumentType.integer(1)), SPAWN_OPTIONS,
									context -> spawnFakeEntity(context, true)))))))
				.then(Commands.literal("remove")
					.then(liveId().executes(IllusionCommand::removeFakeEntity)))
				.then(Commands.literal("remove-all")
					.then(Commands.argument("owner", EntityArgument.entity()).executes(IllusionCommand::removeOwned)))
				.then(Commands.literal("run")
					.then(liveId()
						.then(Commands.argument("command", StringArgumentType.greedyString())
							.executes(IllusionCommand::runAsFakeEntity))))
				.then(Commands.literal("list").executes(IllusionCommand::listFakeEntities)))
			.then(Commands.literal("disguise")
				.then(Commands.argument("targets", EntityArgument.entities())
					.then(CommandOptions.with(entityType(buildContext), DISGUISE_OPTIONS, IllusionCommand::disguise))))
			.then(Commands.literal("undisguise")
				.then(Commands.argument("targets", EntityArgument.entities())
					.executes(IllusionCommand::undisguise)))
		);
	}

	/** An id argument that suggests the ids of live fake entities. */
	private static RequiredArgumentBuilder<CommandSourceStack, Integer> liveId() {
		return Commands.argument("id", IntegerArgumentType.integer(1))
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(FakeEntityTracker.ids().stream().map(String::valueOf), builder));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, ?> entityType(CommandBuildContext buildContext) {
		return Commands.argument("entity_type", ResourceArgument.resource(buildContext, Registries.ENTITY_TYPE))
			.suggests(SuggestionProviders.SUMMONABLE_ENTITIES);
	}

	/**
	 * Returns the fake entity's id, for execute store. A left-out behavior is null: stationary for fake players,
	 * neutral for mobs.
	 */
	private static int spawnFakeEntity(CommandContext<CommandSourceStack> context, boolean fakePlayer) throws CommandSyntaxException {
		FakeEntityTracker.Behavior behavior = null;
		if (CommandOptions.has(context, "behavior")) {
			String name = StringArgumentType.getString(context, "behavior");
			try {
				behavior = FakeEntityTracker.Behavior.valueOf(name.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw BAD_BEHAVIOR.create(name);
			}
		}
		Integer requested = CommandOptions.has(context, "id") ? IntegerArgumentType.getInteger(context, "id") : null;
		Entity owner = EntityArgument.getEntity(context, "owner");
		EntityType<?> type = fakePlayer ? EntityType.PLAYER : type(context);
		String player = fakePlayer ? StringArgumentType.getString(context, "username") : null;
		Vec3 pos = Vec3Argument.getVec3(context, "pos");
		int lifetime = IntegerArgumentType.getInteger(context, "lifetime");
		Predicate<Tuple<Entity, Entity>> viewers = CommandOptions.has(context, "viewers") ? viewers(EntityArgument.getPlayers(context, "viewers")) : null;
		int id = FakeEntityTracker.spawn(type, owner, pos, behavior, viewers, false, lifetime, requested, player, null);
		if (id == 0) {
			throw BAD_TYPE.create(EntityType.getKey(type));
		}
		FakeEntityTracker.Behavior resolved = behavior != null ? behavior
			: fakePlayer ? FakeEntityTracker.Behavior.STATIONARY : FakeEntityTracker.Behavior.NEUTRAL;
		String what = resolved.name().toLowerCase(Locale.ROOT) + (fakePlayer ? " fake player that looks like " + player : " " + EntityType.getKey(type));
		context.getSource().sendSuccess(() -> Component.literal("Spawned fake entity " + id + ": a " + what + " for " + lifetime + " ticks"), true);
		return id;
	}

	/** Runs the command as the fake entity, at its position and facing: @s is the fake entity. Returns the command's result. */
	private static int runAsFakeEntity(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		int id = IntegerArgumentType.getInteger(context, "id");
		Entity fakeEntity = FakeEntityTracker.get(id);
		if (fakeEntity == null) {
			throw NO_FAKE_ENTITY.create(id);
		}
		String command = StringArgumentType.getString(context, "command");
		CommandSourceStack source = context.getSource().withEntity(fakeEntity).withPosition(fakeEntity.position())
			.withRotation(fakeEntity.getRotationVector()).withLevel((net.minecraft.server.level.ServerLevel) fakeEntity.level());
		int[] result = {0};
		FakeEntityTracker.runOn(fakeEntity, () -> result[0] = source.getServer().getCommands().performPrefixedCommand(source, command));
		return result[0];
	}

	private static int removeFakeEntity(CommandContext<CommandSourceStack> context) {
		int id = IntegerArgumentType.getInteger(context, "id");
		boolean removed = FakeEntityTracker.despawn(id);
		context.getSource().sendSuccess(() -> Component.literal(removed ? "Removed the fake entity " + id : "There's no fake entity " + id), true);
		return removed ? 1 : 0;
	}

	private static int removeOwned(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Entity owner = EntityArgument.getEntity(context, "owner");
		int removed = FakeEntityTracker.despawnAllOwnedBy(owner.getUUID());
		context.getSource().sendSuccess(() -> Component.literal("Removed " + removed + (removed == 1 ? " fake entity" : " fake entities")), true);
		return removed;
	}

	private static int listFakeEntities(CommandContext<CommandSourceStack> context) {
		java.util.List<String> lines = FakeEntityTracker.describeAll();
		context.getSource().sendSuccess(() -> Component.literal(lines.size() + (lines.size() == 1 ? " fake entity" : " fake entities")), false);
		for (String line : lines) {
			context.getSource().sendSuccess(() -> Component.literal("  " + line), false);
		}
		return lines.size();
	}

	private static int disguise(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		EntityType<?> type = type(context);
		int duration = CommandOptions.has(context, "duration") ? IntegerArgumentType.getInteger(context, "duration") : 0;
		Predicate<Tuple<Entity, Entity>> viewers = CommandOptions.has(context, "viewers") ? viewers(EntityArgument.getPlayers(context, "viewers")) : null;
		int count = 0;
		for (Entity target : EntityArgument.getEntities(context, "targets")) {
			if (!DisguiseTracker.disguise(target, type, viewers, false, duration)) {
				throw BAD_TYPE.create(EntityType.getKey(type));
			}
			count++;
		}
		int disguised = count;
		context.getSource().sendSuccess(() -> Component.literal("Disguised " + disguised + " entit" + (disguised == 1 ? "y" : "ies")
			+ " as " + EntityType.getKey(type) + (duration > 0 ? " for " + duration + " ticks" : "")), true);
		return count;
	}

	private static int undisguise(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		int count = 0;
		for (Entity target : EntityArgument.getEntities(context, "targets")) {
			if (DisguiseTracker.isDisguised(target)) {
				DisguiseTracker.undisguise(target);
				count++;
			}
		}
		int undisguised = count;
		context.getSource().sendSuccess(() -> Component.literal("Removed " + undisguised + " disguise" + (undisguised == 1 ? "" : "s")), true);
		return count;
	}

	private static EntityType<?> type(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return ResourceArgument.getSummonableEntityType(context, "entity_type").value();
	}

	/** The selected players, fixed now. */
	private static Predicate<Tuple<Entity, Entity>> viewers(Collection<ServerPlayer> players) {
		Set<UUID> ids = new HashSet<>();
		for (ServerPlayer player : players) {
			ids.add(player.getUUID());
		}
		return pair -> ids.contains(pair.getA().getUUID());
	}
}
