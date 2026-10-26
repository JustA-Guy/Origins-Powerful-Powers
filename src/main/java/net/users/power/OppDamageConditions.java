package net.users.power;

import io.github.apace100.apoli.power.factory.condition.ConditionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Tuple;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.users.OriginsPowerfulPowers;

/**
 * Damage conditions only get the DamageSource and amount, with no victim, so checks about the victim (like
 * recent_damage_blocked) live in OppEntityConditions.
 */
public final class OppDamageConditions {
	private OppDamageConditions() {
	}

	public static void register() {
		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("damage_source_nbt"),
			new SerializableData().add("key", SerializableDataTypes.STRING).add("value", SerializableDataTypes.STRING),
			(data, tuple) -> {
				DamageSource source = tuple.getA();
				Entity directEntity = source.getDirectEntity();
				if (directEntity == null) {
					return false;
				}
				CompoundTag tag = directEntity.saveWithoutId(new CompoundTag());
				Tag value = tag.get(data.getString("key"));
				return value != null && value.getAsString().equals(data.getString("value"));
			}
		));
	}

	private static void register(ConditionFactory<Tuple<DamageSource, Float>> factory) {
		Registry.register(ApoliRegistries.DAMAGE_CONDITION, factory.getSerializerId(), factory);
	}
}
