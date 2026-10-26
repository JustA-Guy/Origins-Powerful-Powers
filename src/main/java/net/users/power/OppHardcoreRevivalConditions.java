package net.users.power;

import io.github.apace100.apoli.power.factory.condition.ConditionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import net.minecraft.core.Registry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.users.OriginsPowerfulPowers;
import net.users.compat.hcr.HcrCompat;

/**
 * Optional Hardcore Revival integration. is_downed checks HcrCompat.isLoaded() first, so it's false without the mod.
 */
public final class OppHardcoreRevivalConditions {
	private OppHardcoreRevivalConditions() {
	}

	public static void register() {
		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_downed"),
			new SerializableData(),
			(data, entity) -> entity instanceof Player player && HcrCompat.isLoaded() && HcrCompat.isDowned(player)
		));
	}

	private static void register(ConditionFactory<Entity> factory) {
		Registry.register(ApoliRegistries.ENTITY_CONDITION, factory.getSerializerId(), factory);
	}
}
