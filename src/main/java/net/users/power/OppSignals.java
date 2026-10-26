package net.users.power;

import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.apoli.power.factory.action.ActionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.users.OriginsPowerfulPowers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Signals: one power announces something by name, and unrelated powers react.
 *   origins-powerful-powers:emit_signal       entity action: signal, optional radius (left out: every loaded entity everywhere)
 *   origins-powerful-powers:action_on_signal  power type: signals (exact names or * wildcards), actions; actor = listener,
 *                                         target = the sender
 * The sender's own listeners never fire. Listeners are looked up when the signal is sent (entities in the radius, or
 * all loaded entities), so an unloaded or removed entity can never be reached by mistake.
 */
public final class OppSignals {
	/** A listener that emits a signal runs inside the first one; past this depth further signals are dropped. */
	private static final int MAX_DEPTH = 8;
	private static int depth;

	private OppSignals() {
	}

	/** Signal names get origins-powerful-powers: when they have no namespace, like global keys. */
	static String normalize(String name) {
		String trimmed = name.trim().toLowerCase(java.util.Locale.ROOT);
		return trimmed.indexOf(':') < 0 && !trimmed.equals("*") ? OriginsPowerfulPowers.MOD_ID + ":" + trimmed : trimmed;
	}

	public static class SignalPower extends Power {
		private final List<Pattern> patterns = new ArrayList<>();
		private final Consumer<Entity> entityAction;
		private final Consumer<Tuple<Entity, Entity>> bientityAction;
		private final Predicate<Tuple<Entity, Entity>> bientityCondition;

		SignalPower(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
			super(type, entity);
			List<String> signals = data.get("signals");
			for (String signal : signals) {
				patterns.add(toPattern(normalize(signal)));
			}
			this.entityAction = data.get("entity_action");
			this.bientityAction = data.get("bientity_action");
			this.bientityCondition = data.get("bientity_condition");
		}

		boolean listensTo(String signal) {
			for (Pattern pattern : patterns) {
				if (pattern.matcher(signal).matches()) {
					return true;
				}
			}
			return false;
		}

		void receive(Entity sender) {
			Tuple<Entity, Entity> pair = new Tuple<>(entity, sender);
			if (bientityCondition != null && !bientityCondition.test(pair)) {
				return;
			}
			if (entityAction != null) {
				entityAction.accept(entity);
			}
			if (bientityAction != null) {
				bientityAction.accept(pair);
			}
		}

		/** * matches any run of characters (including : and /); everything else is literal. */
		private static Pattern toPattern(String signal) {
			List<String> quoted = new ArrayList<>();
			for (String part : signal.split("\\*", -1)) {
				quoted.add(Pattern.quote(part));
			}
			return Pattern.compile(String.join(".*", quoted));
		}
	}

	public static void register() {
		PowerFactory<SignalPower> factory = new PowerFactory<SignalPower>(
			OriginsPowerfulPowers.id("action_on_signal"),
			new SerializableData()
				.add("signals", SerializableDataTypes.STRINGS)
				.add("entity_action", ApoliDataTypes.ENTITY_ACTION, null)
				.add("bientity_action", ApoliDataTypes.BIENTITY_ACTION, null)
				.add("bientity_condition", ApoliDataTypes.BIENTITY_CONDITION, null),
			instance -> (type, entity) -> new SignalPower(type, entity, instance)
		).allowCondition();
		Registry.register(ApoliRegistries.POWER_FACTORY, factory.getSerializerId(), factory);

		ActionFactory<Entity> emit = new ActionFactory<>(
			OriginsPowerfulPowers.id("emit_signal"),
			new SerializableData()
				.add("signal", SerializableDataTypes.STRING)
				.add("radius", SerializableDataTypes.DOUBLE, null),
			(data, entity) -> emit(entity, normalize(data.getString("signal")), data.get("radius"))
		);
		Registry.register(ApoliRegistries.ENTITY_ACTION, emit.getSerializerId(), emit);
	}

	/** Sends signal from sender to every matching active listener, within radius blocks in the same dimension, or everywhere if radius is null. */
	public static int emit(Entity sender, String signal, Double radius) {
		if (!(sender.level() instanceof ServerLevel senderLevel)) {
			return 0;
		}
		if (depth >= MAX_DEPTH) {
			OriginsPowerfulPowers.LOGGER.warn("[signals] {} was sent more than {} levels deep inside other signals; dropped to stop a loop", signal, MAX_DEPTH);
			return 0;
		}
		List<SignalPower> heard = new ArrayList<>();
		if (radius != null) {
			AABB box = sender.getBoundingBox().inflate(radius);
			double radiusSqr = radius * radius;
			for (LivingEntity listener : senderLevel.getEntitiesOfClass(LivingEntity.class, box,
					candidate -> candidate != sender && candidate.distanceToSqr(sender) <= radiusSqr)) {
				collect(listener, signal, heard);
			}
		} else {
			MinecraftServer server = senderLevel.getServer();
			for (ServerLevel level : server.getAllLevels()) {
				for (Entity listener : level.getAllEntities()) {
					if (listener != sender && listener instanceof LivingEntity) {
						collect(listener, signal, heard);
					}
				}
			}
		}
		depth++;
		try {
			for (SignalPower power : heard) {
				power.receive(sender);
			}
		} finally {
			depth--;
		}
		return heard.size();
	}

	private static void collect(Entity listener, String signal, List<SignalPower> heard) {
		for (SignalPower power : PowerHolderComponent.getPowers(listener, SignalPower.class)) {
			if (power.listensTo(signal)) {
				heard.add(power);
			}
		}
	}
}
