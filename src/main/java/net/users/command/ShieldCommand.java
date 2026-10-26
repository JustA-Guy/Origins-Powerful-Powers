package net.users.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.Collection;

/**
 * /disableshield <targets> <ticks>
 * Puts the shield on cooldown and forces it down. Only players blocking right now are affected, since it's meant for
 * ability effects like a shield bash.
 */
public final class ShieldCommand {
	private ShieldCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("disableshield")
			.requires(source -> source.hasPermission(2))
			.then(Commands.argument("targets", EntityArgument.players())
				.then(Commands.argument("ticks", IntegerArgumentType.integer(1))
					.executes(context -> {
						Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
						int ticks = IntegerArgumentType.getInteger(context, "ticks");
						int affected = 0;
						for (ServerPlayer player : targets) {
							if (player.isBlocking()) {
								player.getCooldowns().addCooldown(Items.SHIELD, ticks);
								player.stopUsingItem();
								player.level().broadcastEntityEvent(player, (byte) 30);
								affected++;
							}
						}
						int finalAffected = affected;
						context.getSource().sendSuccess(
							() -> Component.literal("Disabled shield for " + finalAffected + " of " + targets.size() + " target(s)."),
							true
						);
						return finalAffected;
					})
				)
			)
		);
	}
}
