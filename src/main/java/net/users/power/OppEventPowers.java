package net.users.power;

import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.util.Tuple;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.users.OriginsPowerfulPowers;
import net.users.tracking.LastDamageTracker;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Trigger powers for Fabric events Apoli has no power for. Each runs its actions when the event
 * happens to the holder; all are server-side.
 *   action_on_death:           the holder died. bientity: actor = holder, target = the killer (if any)
 *   action_on_join:            the holder (a player) joined the server
 *   action_on_disconnect:      the holder (a player) is leaving; runs before the player is saved
 *   action_on_start_tracking:  the server started sending an entity to the holder (a player).
 *                              bientity: actor = holder, target = that entity
 *   action_on_mob_conversion:  the holder (a mob) converted into another mob, e.g. a cured zombie villager.
 *                              bientity: actor = holder (the old mob), target = the new mob
 *   action_on_sleep_start:     the holder started sleeping (players in a bed; villagers too)
 */
public final class OppEventPowers {
	private OppEventPowers() {
	}

	/** Any field left out of the JSON is null and skipped. */
	public static class EventPower extends Power {
		private final Consumer<Entity> entityAction;
		private final Consumer<Tuple<Entity, Entity>> bientityAction;
		private final Predicate<Tuple<Entity, Entity>> bientityCondition;
		private final Predicate<Tuple<DamageSource, Float>> damageCondition;

		EventPower(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
			super(type, entity);
			this.entityAction = optional(data, "entity_action");
			this.bientityAction = optional(data, "bientity_action");
			this.bientityCondition = optional(data, "bientity_condition");
			this.damageCondition = optional(data, "damage_condition");
		}

		/** Not every power type declares every field. */
		private static <T> T optional(SerializableData.Instance data, String name) {
			return data.isPresent(name) ? data.get(name) : null;
		}

		void fire(Entity other, DamageSource source, float amount) {
			if (damageCondition != null && !damageCondition.test(new Tuple<>(source, amount))) {
				return;
			}
			Tuple<Entity, Entity> pair = new Tuple<>(entity, other);
			if (bientityCondition != null && (other == null || !bientityCondition.test(pair))) {
				return;
			}
			if (entityAction != null) {
				entityAction.accept(entity);
			}
			if (bientityAction != null && other != null) {
				bientityAction.accept(pair);
			}
		}
	}

	public static class Death extends EventPower {
		Death(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
			super(type, entity, data);
		}
	}

	public static class Join extends EventPower {
		Join(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
			super(type, entity, data);
		}
	}

	public static class Disconnect extends EventPower {
		Disconnect(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
			super(type, entity, data);
		}
	}

	public static class StartTracking extends EventPower {
		StartTracking(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
			super(type, entity, data);
		}
	}

	public static class MobConversion extends EventPower {
		MobConversion(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
			super(type, entity, data);
		}
	}

	public static class SleepStart extends EventPower {
		SleepStart(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
			super(type, entity, data);
		}
	}

	@FunctionalInterface
	private interface Constructor<P extends EventPower> {
		P create(PowerType<?> type, LivingEntity entity, SerializableData.Instance data);
	}

	public static void register() {
		registerPower("action_on_death", Death::new, bientityData()
			.add("entity_action", ApoliDataTypes.ENTITY_ACTION, null)
			.add("damage_condition", ApoliDataTypes.DAMAGE_CONDITION, null));
		registerPower("action_on_join", Join::new, entityData());
		registerPower("action_on_disconnect", Disconnect::new, entityData());
		registerPower("action_on_start_tracking", StartTracking::new, bientityData());
		registerPower("action_on_mob_conversion", MobConversion::new, bientityData());
		registerPower("action_on_sleep_start", SleepStart::new, entityData());

		// The amount is the one OPP recorded at the start of the killing hurt() call, before armor.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) ->
			fire(entity, Death.class, source.getEntity(), source, LastDamageTracker.getAmount(entity)));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			fire(handler.player, Join.class, null, null, 0.0F));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			fire(handler.player, Disconnect.class, null, null, 0.0F));
		EntityTrackingEvents.START_TRACKING.register((tracked, player) ->
			fire(player, StartTracking.class, tracked, null, 0.0F));
		ServerLivingEntityEvents.MOB_CONVERSION.register((previous, converted, keepEquipment) ->
			fire(previous, MobConversion.class, converted, null, 0.0F));
		// Fabric fires this from LivingEntity#startSleeping; skip any client-side call so actions only run once, on the server.
		EntitySleepEvents.START_SLEEPING.register((entity, bedPos) -> {
			if (!entity.level().isClientSide()) {
				fire(entity, SleepStart.class, null, null, 0.0F);
			}
		});
	}

	private static <P extends EventPower> void fire(Entity holder, Class<P> powerClass, Entity other, DamageSource source, float amount) {
		for (P power : PowerHolderComponent.getPowers(holder, powerClass)) {
			power.fire(other, source, amount);
		}
	}

	private static SerializableData entityData() {
		return new SerializableData().add("entity_action", ApoliDataTypes.ENTITY_ACTION, null);
	}

	private static SerializableData bientityData() {
		return new SerializableData()
			.add("bientity_action", ApoliDataTypes.BIENTITY_ACTION, null)
			.add("bientity_condition", ApoliDataTypes.BIENTITY_CONDITION, null);
	}

	private static <P extends EventPower> void registerPower(String name, Constructor<P> constructor, SerializableData data) {
		PowerFactory<P> factory = new PowerFactory<P>(
			OriginsPowerfulPowers.id(name),
			data,
			instance -> (type, entity) -> constructor.create(type, entity, instance)
		).allowCondition();
		Registry.register(ApoliRegistries.POWER_FACTORY, factory.getSerializerId(), factory);
	}
}
