package net.users.power;

import io.github.apace100.apoli.power.factory.condition.ConditionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.Registry;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.users.OriginsPowerfulPowers;
import net.users.compat.svc.SvcCompat;
import net.users.tracking.BroadcastTracker;
import net.users.tracking.DeafenTracker;
import net.users.tracking.MuteTracker;
import net.users.tracking.VoiceChatTracker;

import java.util.Objects;
import java.util.UUID;

/**
 * Optional Simple Voice Chat integration. Conditions reading SVC state check SvcCompat.isLoaded() first, so they're
 * false without SVC. is_broadcasting_voice reads OPP's own BroadcastTracker, so it works without SVC, for example to
 * drive particles.
 */
public final class OppVoicechatConditions {
	private OppVoicechatConditions() {
	}

	public static void register() {
		registerEntity(new ConditionFactory<>(
			OriginsPowerfulPowers.id("voice_installed"),
			new SerializableData(),
			(data, entity) -> SvcCompat.isLoaded() && VoiceChatTracker.isInstalled(entity.getUUID())
		));

		registerEntity(new ConditionFactory<>(
			OriginsPowerfulPowers.id("voice_mic_disabled"),
			new SerializableData(),
			(data, entity) -> SvcCompat.isLoaded() && VoiceChatTracker.isMicDisabled(entity.getUUID())
		));

		registerEntity(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_talking"),
			new SerializableData(),
			(data, entity) -> SvcCompat.isLoaded() && VoiceChatTracker.isTalking(entity.getUUID())
		));

		registerEntity(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_whispering"),
			new SerializableData(),
			(data, entity) -> SvcCompat.isLoaded() && VoiceChatTracker.isWhispering(entity.getUUID())
		));

		registerEntity(new ConditionFactory<>(
			OriginsPowerfulPowers.id("in_voice_group"),
			new SerializableData(),
			(data, entity) -> SvcCompat.isLoaded() && VoiceChatTracker.isInGroup(entity.getUUID())
		));

		registerBiEntity(new ConditionFactory<>(
			OriginsPowerfulPowers.id("in_same_voice_group"),
			new SerializableData(),
			(data, pair) -> {
				if (!SvcCompat.isLoaded()) {
					return false;
				}
				UUID groupA = VoiceChatTracker.getGroup(pair.getA().getUUID());
				UUID groupB = VoiceChatTracker.getGroup(pair.getB().getUUID());
				return groupA != null && Objects.equals(groupA, groupB);
			}
		));

		registerEntity(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_broadcasting_voice"),
			new SerializableData(),
			(data, entity) -> BroadcastTracker.isActive(entity.getUUID())
		));

		registerEntity(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_muted_voice"),
			new SerializableData(),
			(data, entity) -> MuteTracker.isMuted(entity.getUUID())
		));

		registerEntity(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_deafened_voice"),
			new SerializableData(),
			(data, entity) -> DeafenTracker.isDeafened(entity.getUUID())
		));

		registerBiEntity(new ConditionFactory<>(
			OriginsPowerfulPowers.id("in_voice_range_of"),
			new SerializableData().add("max_distance", SerializableDataTypes.DOUBLE, -1.0),
			(data, pair) -> {
				if (!SvcCompat.isLoaded()) {
					return false;
				}
				double maxDistance = data.getDouble("max_distance");
				double range = maxDistance >= 0.0 ? maxDistance : SvcCompat.getVoiceChatDistance();
				return pair.getA().distanceTo(pair.getB()) <= range;
			}
		));
	}

	private static void registerEntity(ConditionFactory<Entity> factory) {
		Registry.register(ApoliRegistries.ENTITY_CONDITION, factory.getSerializerId(), factory);
	}

	private static void registerBiEntity(ConditionFactory<Tuple<Entity, Entity>> factory) {
		Registry.register(ApoliRegistries.BIENTITY_CONDITION, factory.getSerializerId(), factory);
	}
}
