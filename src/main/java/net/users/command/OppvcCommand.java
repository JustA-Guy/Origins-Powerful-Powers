package net.users.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.users.tracking.BroadcastTracker;
import net.users.tracking.DeafenTracker;
import net.users.tracking.MuteTracker;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * /oppvc broadcast start [listeners]   the command's own player broadcasts to listeners (default: everyone)
 * /oppvc broadcast stop                stops the command's own player's broadcast, if any
 * /oppvc mute start|stop <targets>
 * /oppvc deafen start|stop <targets>
 *
 * Server-side voice chat controls that need no power. OppVoicechatPlugin enforces each one.
 */
public final class OppvcCommand {
	private OppvcCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("oppvc")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("broadcast")
				.then(Commands.literal("start")
					.executes(context -> startBroadcast(context, null))
					.then(Commands.argument("listeners", EntityArgument.players())
						.executes(context -> startBroadcast(context, EntityArgument.getPlayers(context, "listeners")))
					)
				)
				.then(Commands.literal("stop")
					.executes(context -> {
						ServerPlayer self = context.getSource().getPlayerOrException();
						BroadcastTracker.stop(self);
						context.getSource().sendSuccess(() -> Component.literal("Stopped broadcasting."), true);
						return 1;
					})
				)
			)
			.then(Commands.literal("mute")
				.then(Commands.literal("start")
					.then(Commands.argument("targets", EntityArgument.players())
						.executes(context -> {
							Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
							targets.forEach(MuteTracker::mute);
							return report(context, "Muted", targets.size());
						})
					)
				)
				.then(Commands.literal("stop")
					.then(Commands.argument("targets", EntityArgument.players())
						.executes(context -> {
							Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
							targets.forEach(MuteTracker::unmute);
							return report(context, "Unmuted", targets.size());
						})
					)
				)
			)
			.then(Commands.literal("deafen")
				.then(Commands.literal("start")
					.then(Commands.argument("targets", EntityArgument.players())
						.executes(context -> {
							Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
							targets.forEach(DeafenTracker::deafen);
							return report(context, "Deafened", targets.size());
						})
					)
				)
				.then(Commands.literal("stop")
					.then(Commands.argument("targets", EntityArgument.players())
						.executes(context -> {
							Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
							targets.forEach(DeafenTracker::undeafen);
							return report(context, "Undeafened", targets.size());
						})
					)
				)
			)
		);
	}

	private static int startBroadcast(CommandContext<CommandSourceStack> context, Collection<ServerPlayer> listeners) throws CommandSyntaxException {
		ServerPlayer self = context.getSource().getPlayerOrException();
		if (listeners == null) {
			BroadcastTracker.start(self, pair -> true, Long.MAX_VALUE);
			context.getSource().sendSuccess(() -> Component.literal("Started broadcasting to everyone."), true);
		} else {
			Set<UUID> listenerIds = new HashSet<>();
			for (ServerPlayer listener : listeners) {
				listenerIds.add(listener.getUUID());
			}
			BroadcastTracker.start(self, (Tuple<Entity, Entity> pair) -> listenerIds.contains(pair.getB().getUUID()), Long.MAX_VALUE);
			int count = listeners.size();
			context.getSource().sendSuccess(() -> Component.literal("Started broadcasting to " + count + " player(s)."), true);
		}
		return 1;
	}

	private static int report(CommandContext<CommandSourceStack> context, String verb, int count) {
		context.getSource().sendSuccess(() -> Component.literal(verb + " " + count + " player(s)."), true);
		return count;
	}
}
