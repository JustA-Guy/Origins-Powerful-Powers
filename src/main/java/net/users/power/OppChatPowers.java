package net.users.power;

import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.users.OriginsPowerfulPowers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * origins-powerful-powers:action_on_chat: runs actions when a chat
 * message contains the power's substrings.
 *
 * Outgoing (the holder sent it) is checked in ALLOW_CHAT_MESSAGE, so a matching power with hide_message can cancel
 * the broadcast. Incoming (the holder received it) is checked in CHAT_MESSAGE, which only fires for messages that
 * were broadcast, so a hidden message never triggers incoming powers. PlayerList#broadcastChatMessage sends every
 * chat message to every online player (its predicate only picks filtered text), so the receivers are all online
 * players except the sender. /say and /me don't fire these events.
 */
public final class OppChatPowers {
	public enum Match {
		ANY, ALL
	}

	public enum Direction {
		OUTGOING, INCOMING, BOTH
	}

	private static final SerializableDataType<Match> MATCH = SerializableDataType.enumValue(Match.class);
	private static final SerializableDataType<Direction> DIRECTION = SerializableDataType.enumValue(Direction.class);

	private OppChatPowers() {
	}

	public static class ChatPower extends Power {
		private final List<String> substrings;
		private final Match match;
		private final boolean ordered;
		private final boolean caseSensitive;
		private final Direction direction;
		private final boolean hideMessage;
		private final Consumer<Entity> entityAction;
		private final Consumer<Tuple<Entity, Entity>> bientityAction;
		private final Predicate<Tuple<Entity, Entity>> bientityCondition;

		ChatPower(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
			super(type, entity);
			this.caseSensitive = data.getBoolean("case_sensitive");
			List<String> raw = data.get("substrings");
			this.substrings = new ArrayList<>();
			for (String substring : raw) {
				substrings.add(caseSensitive ? substring : substring.toLowerCase(Locale.ROOT));
			}
			this.match = data.get("match");
			this.ordered = data.getBoolean("ordered");
			this.direction = data.get("direction");
			this.hideMessage = data.getBoolean("hide_message");
			this.entityAction = data.get("entity_action");
			this.bientityAction = data.get("bientity_action");
			this.bientityCondition = data.get("bientity_condition");
		}

		boolean listensTo(Direction side) {
			return direction == Direction.BOTH || direction == side;
		}

		boolean matches(String text) {
			if (substrings.isEmpty()) {
				return false;
			}
			String haystack = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
			if (match == Match.ANY) {
				for (String substring : substrings) {
					if (haystack.contains(substring)) {
						return true;
					}
				}
				return false;
			}
			int from = 0;
			for (String substring : substrings) {
				int at = haystack.indexOf(substring, ordered ? from : 0);
				if (at < 0) {
					return false;
				}
				from = at + substring.length();
			}
			return true;
		}

		/** entity_action once on the holder; bientity_action once per other player that passes bientity_condition. */
		void fire(List<ServerPlayer> others) {
			if (entityAction != null) {
				entityAction.accept(entity);
			}
			if (bientityAction == null) {
				return;
			}
			for (ServerPlayer other : others) {
				Tuple<Entity, Entity> pair = new Tuple<>(entity, other);
				if (bientityCondition == null || bientityCondition.test(pair)) {
					bientityAction.accept(pair);
				}
			}
		}
	}

	public static void register() {
		PowerFactory<ChatPower> factory = new PowerFactory<ChatPower>(
			OriginsPowerfulPowers.id("action_on_chat"),
			new SerializableData()
				.add("substrings", SerializableDataTypes.STRINGS)
				.add("match", MATCH, Match.ANY)
				.add("ordered", SerializableDataTypes.BOOLEAN, false)
				.add("case_sensitive", SerializableDataTypes.BOOLEAN, true)
				.add("direction", DIRECTION, Direction.BOTH)
				.add("hide_message", SerializableDataTypes.BOOLEAN, false)
				.add("entity_action", ApoliDataTypes.ENTITY_ACTION, null)
				.add("bientity_action", ApoliDataTypes.BIENTITY_ACTION, null)
				.add("bientity_condition", ApoliDataTypes.BIENTITY_CONDITION, null),
			instance -> (type, entity) -> new ChatPower(type, entity, instance)
		).allowCondition();
		Registry.register(ApoliRegistries.POWER_FACTORY, factory.getSerializerId(), factory);

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			String text = message.signedContent();
			List<ChatPower> matched = new ArrayList<>();
			boolean hide = false;
			for (ChatPower power : PowerHolderComponent.getPowers(sender, ChatPower.class)) {
				if (power.listensTo(Direction.OUTGOING) && power.matches(text)) {
					matched.add(power);
					hide |= power.hideMessage;
				}
			}
			if (!matched.isEmpty()) {
				List<ServerPlayer> receivers = othersThan(sender);
				runOnServer(sender.server, () -> matched.forEach(power -> power.fire(receivers)));
			}
			return !hide;
		});

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			String text = message.signedContent();
			List<ServerPlayer> senderOnly = List.of(sender);
			for (ServerPlayer receiver : othersThan(sender)) {
				List<ChatPower> matched = new ArrayList<>();
				for (ChatPower power : PowerHolderComponent.getPowers(receiver, ChatPower.class)) {
					if (power.listensTo(Direction.INCOMING) && power.matches(text)) {
						matched.add(power);
					}
				}
				if (!matched.isEmpty()) {
					runOnServer(sender.server, () -> matched.forEach(power -> power.fire(senderOnly)));
				}
			}
		});
	}

	private static List<ServerPlayer> othersThan(ServerPlayer sender) {
		List<ServerPlayer> others = new ArrayList<>(sender.server.getPlayerList().getPlayers());
		others.remove(sender);
		return others;
	}

	/** Chat is handled on the server thread in 1.20.1, but actions must never run anywhere else, so check. */
	private static void runOnServer(MinecraftServer server, Runnable task) {
		if (server.isSameThread()) {
			task.run();
		} else {
			server.execute(task);
		}
	}
}
