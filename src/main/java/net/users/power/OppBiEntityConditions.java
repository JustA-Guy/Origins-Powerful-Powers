package net.users.power;

import io.github.apace100.apoli.power.factory.condition.ConditionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.Registry;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.users.OriginsPowerfulPowers;

/**
 * getA() is the power holder (self) and getB() the other entity, as in Apoli's own bientity conditions.
 */
public final class OppBiEntityConditions {
	private OppBiEntityConditions() {
	}

	public static void register() {
		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_observed_by"),
			new SerializableData()
				.add("fov_degrees", SerializableDataTypes.FLOAT, 60.0F)
				.add("max_distance", SerializableDataTypes.FLOAT, 32.0F),
			(data, pair) -> {
				Entity self = pair.getA();
				Entity other = pair.getB();
				double maxDistance = data.getFloat("max_distance");
				if (self.distanceTo(other) > maxDistance) {
					return false;
				}
				Vec3 toSelf = self.position().subtract(other.position()).normalize();
				Vec3 otherView = other.getViewVector(1.0F);
				double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, toSelf.dot(otherView)))));
				return angle <= data.getFloat("fov_degrees") / 2.0F;
			}
		));
		// Checks the target's powers, so a power can react to what the other entity is.
		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("has_power_tag"),
			new SerializableData()
				.add("tag", PowerTags.TAG_NAME)
				.add("include_inactive", SerializableDataTypes.BOOLEAN, false),
			(data, pair) -> PowerTags.has(pair.getB(), data.getString("tag"), data.getBoolean("include_inactive"))
		));
		// is_targeting was removed in 2.1.0: origins:attack_target does the same, and covers goal-based mobs.
	}

	private static void register(ConditionFactory<Tuple<Entity, Entity>> factory) {
		Registry.register(ApoliRegistries.BIENTITY_CONDITION, factory.getSerializerId(), factory);
	}
}
