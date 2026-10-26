package net.users.power;

import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.apoli.power.factory.action.ActionFactory;
import io.github.apace100.apoli.power.factory.condition.ConditionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.apoli.util.Comparison;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.users.OriginsPowerfulPowers;
import net.users.tracking.FakeEntityTracker;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Ways for powers to reach fake entities, which are never in the world, so
 * selectors, area_of_effect and raycasts can't find them. Each hands the fake entity's own Entity object to Apoli:
 *   fake_entity_action          entity action: runs actions on the holder's fake entities (or one by id).
 *                           bientity: actor = the entity running it, target = the fake entity
 *   fake entity                 entity condition: how many of the holder's fake entities (or the one by id) match
 *   action_on_fake_entity_hit   power: a viewer hit one of the holder's fake entities.
 *                           bientity: actor = the player who hit it, target = the fake entity
 */
public final class FakeEntityPowers {
	private FakeEntityPowers() {
	}

	public static class FakeEntityHitPower extends Power {
		private final Integer id;
		private final Consumer<Entity> entityAction;
		private final Consumer<Tuple<Entity, Entity>> bientityAction;
		private final Predicate<Tuple<Entity, Entity>> bientityCondition;

		FakeEntityHitPower(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
			super(type, entity);
			this.id = data.get("id");
			this.entityAction = data.get("entity_action");
			this.bientityAction = data.get("bientity_action");
			this.bientityCondition = data.get("bientity_condition");
		}

		void fire(int fakeEntityId, Entity fakeEntity, ServerPlayer attacker) {
			if (id != null && id != fakeEntityId) {
				return;
			}
			Tuple<Entity, Entity> pair = new Tuple<>(attacker, fakeEntity);
			if (bientityCondition != null && !bientityCondition.test(pair)) {
				return;
			}
			if (entityAction != null) {
				entityAction.accept(entity);
			}
			if (bientityAction != null) {
				FakeEntityTracker.runOn(fakeEntity, () -> bientityAction.accept(pair));
			}
		}
	}

	public static void register() {
		ActionFactory<Entity> action = new ActionFactory<>(
			OriginsPowerfulPowers.id("fake_entity_action"),
			new SerializableData()
				.add("id", SerializableDataTypes.INT, null)
				.add("fake_entity_condition", ApoliDataTypes.ENTITY_CONDITION, null)
				.add("entity_action", ApoliDataTypes.ENTITY_ACTION, null)
				.add("bientity_action", ApoliDataTypes.BIENTITY_ACTION, null),
			(data, entity) -> {
				Predicate<Entity> condition = data.get("fake_entity_condition");
				Consumer<Entity> entityAction = data.get("entity_action");
				Consumer<Tuple<Entity, Entity>> bientityAction = data.get("bientity_action");
				for (Entity fakeEntity : FakeEntityTracker.find(entity, data.get("id"))) {
					if (condition != null && !condition.test(fakeEntity)) {
						continue;
					}
					FakeEntityTracker.runOn(fakeEntity, () -> {
						if (entityAction != null) {
							entityAction.accept(fakeEntity);
						}
						if (bientityAction != null) {
							bientityAction.accept(new Tuple<>(entity, fakeEntity));
						}
					});
				}
			}
		);
		Registry.register(ApoliRegistries.ENTITY_ACTION, action.getSerializerId(), action);

		ConditionFactory<Entity> condition = new ConditionFactory<>(
			OriginsPowerfulPowers.id("fake_entity"),
			new SerializableData()
				.add("id", SerializableDataTypes.INT, null)
				.add("entity_condition", ApoliDataTypes.ENTITY_CONDITION, null)
				.add("bientity_condition", ApoliDataTypes.BIENTITY_CONDITION, null)
				.add("comparison", ApoliDataTypes.COMPARISON, Comparison.GREATER_THAN_OR_EQUAL)
				.add("compare_to", SerializableDataTypes.INT, 1),
			(data, entity) -> {
				Predicate<Entity> entityCondition = data.get("entity_condition");
				Predicate<Tuple<Entity, Entity>> bientityCondition = data.get("bientity_condition");
				int count = 0;
				for (Entity fakeEntity : FakeEntityTracker.find(entity, data.get("id"))) {
					if ((entityCondition == null || entityCondition.test(fakeEntity))
						&& (bientityCondition == null || bientityCondition.test(new Tuple<>(entity, fakeEntity)))) {
						count++;
					}
				}
				return ((Comparison) data.get("comparison")).compare(count, data.getInt("compare_to"));
			}
		);
		Registry.register(ApoliRegistries.ENTITY_CONDITION, condition.getSerializerId(), condition);

		PowerFactory<FakeEntityHitPower> power = new PowerFactory<FakeEntityHitPower>(
			OriginsPowerfulPowers.id("action_on_fake_entity_hit"),
			new SerializableData()
				.add("id", SerializableDataTypes.INT, null)
				.add("entity_action", ApoliDataTypes.ENTITY_ACTION, null)
				.add("bientity_action", ApoliDataTypes.BIENTITY_ACTION, null)
				.add("bientity_condition", ApoliDataTypes.BIENTITY_CONDITION, null),
			instance -> (type, entity) -> new FakeEntityHitPower(type, entity, instance)
		).allowCondition();
		Registry.register(ApoliRegistries.POWER_FACTORY, power.getSerializerId(), power);
	}

	/** Called by FakeEntityTracker when a viewer hits a fake entity: runs the owner's action_on_fake_entity_hit powers. */
	public static void onHit(Entity owner, int fakeEntityId, Entity fakeEntity, ServerPlayer attacker) {
		if (owner == null || owner.isRemoved()) {
			return;
		}
		for (FakeEntityHitPower power : PowerHolderComponent.getPowers(owner, FakeEntityHitPower.class)) {
			power.fire(fakeEntityId, fakeEntity, attacker);
		}
	}
}
