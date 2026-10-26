package net.users.power;

import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.apoli.power.factory.action.ActionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.users.OriginsPowerfulPowers;
import net.users.tracking.PlayerMaskTracker;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Power types and actions for PlayerMaskTracker:
 *   obfuscate_name      power: the holder's name and nickname show as magic text to viewers passing viewer_condition
 *                       and the list/whitelist filter
 *   hidden_from_tablist power: the holder is left out of the tab list of viewers passing viewer_condition
 *   hide_from_tablist / show_in_tablist  actions: the same, with a duration, and its undo
 * They only do anything on players.
 */
public final class PlayerMaskPowers {
	/** A power keeps its rule for this long after its last refresh, so a logged-out or unloaded holder's rule lapses. */
	private static final int TTL = 40;
	private static final int REFRESH = 10;

	private PlayerMaskPowers() {
	}

	public static void register() {
		PowerFactory<MaskPower> obfuscate = new PowerFactory<MaskPower>(
			OriginsPowerfulPowers.id("obfuscate_name"),
			new SerializableData()
				.add("viewer_condition", ApoliDataTypes.BIENTITY_CONDITION, null)
				.add("list", SerializableDataTypes.STRINGS, null)
				.add("whitelist", SerializableDataTypes.BOOLEAN, true),
			data -> (type, entity) -> new MaskPower(type, entity, PlayerMaskTracker.Kind.OBFUSCATE,
				viewers(data.get("viewer_condition"), data.get("list"), data.getBoolean("whitelist")))
		).allowCondition();
		Registry.register(ApoliRegistries.POWER_FACTORY, obfuscate.getSerializerId(), obfuscate);

		PowerFactory<MaskPower> hidden = new PowerFactory<MaskPower>(
			OriginsPowerfulPowers.id("hidden_from_tablist"),
			new SerializableData().add("viewer_condition", ApoliDataTypes.BIENTITY_CONDITION, null),
			data -> (type, entity) -> new MaskPower(type, entity, PlayerMaskTracker.Kind.TAB_HIDE,
				viewers(data.get("viewer_condition"), null, true))
		).allowCondition();
		Registry.register(ApoliRegistries.POWER_FACTORY, hidden.getSerializerId(), hidden);

		Registry.register(ApoliRegistries.ENTITY_ACTION, OriginsPowerfulPowers.id("hide_from_tablist"), new ActionFactory<Entity>(
			OriginsPowerfulPowers.id("hide_from_tablist"),
			new SerializableData()
				.add("viewer_condition", ApoliDataTypes.BIENTITY_CONDITION, null)
				.add("duration", SerializableDataTypes.INT, 0),
			(data, entity) -> {
				if (entity instanceof ServerPlayer player) {
					PlayerMaskTracker.put(PlayerMaskTracker.Kind.TAB_HIDE, player.getUUID(), PlayerMaskTracker.MANUAL,
						viewers(data.get("viewer_condition"), null, true), data.getInt("duration"));
				}
			}
		));

		Registry.register(ApoliRegistries.ENTITY_ACTION, OriginsPowerfulPowers.id("show_in_tablist"), new ActionFactory<Entity>(
			OriginsPowerfulPowers.id("show_in_tablist"),
			new SerializableData(),
			(data, entity) -> PlayerMaskTracker.remove(PlayerMaskTracker.Kind.TAB_HIDE, entity.getUUID(), PlayerMaskTracker.MANUAL)
		));
	}

	/**
	 * viewer_condition gets the viewer as actor and the masked player as target. list names viewers by username:
	 * whitelist true means only they are masked from, false means everyone but them.
	 */
	static PlayerMaskTracker.ViewerTest viewers(Predicate<Tuple<Entity, Entity>> condition, List<String> list, boolean whitelist) {
		Set<String> names = new HashSet<>();
		if (list != null) {
			list.forEach(name -> names.add(name.toLowerCase(Locale.ROOT)));
		}
		boolean filtered = list != null;
		return (viewer, holder) -> {
			if (filtered && names.contains(viewer.getGameProfile().getName().toLowerCase(Locale.ROOT)) != whitelist) {
				return false;
			}
			return condition == null || condition.test(new Tuple<>(viewer, holder));
		};
	}

	/** Keeps its rule in PlayerMaskTracker while active; drops it as soon as it isn't, or is revoked. */
	public static class MaskPower extends Power {
		private final PlayerMaskTracker.Kind kind;
		private final PlayerMaskTracker.ViewerTest viewers;

		MaskPower(PowerType<?> type, LivingEntity entity, PlayerMaskTracker.Kind kind, PlayerMaskTracker.ViewerTest viewers) {
			super(type, entity);
			this.kind = kind;
			this.viewers = viewers;
		}

		@Override
		public boolean shouldTick() {
			return true;
		}

		@Override
		public boolean shouldTickWhenInactive() {
			return true;
		}

		@Override
		public void tick() {
			if (!(entity instanceof ServerPlayer)) {
				return;
			}
			if (isActive()) {
				if (entity.tickCount % REFRESH == 0) {
					PlayerMaskTracker.refreshPower(kind, entity.getUUID(), this, viewers, TTL);
				}
			} else {
				PlayerMaskTracker.remove(kind, entity.getUUID(), this);
			}
		}

		@Override
		public void onLost() {
			PlayerMaskTracker.remove(kind, entity.getUUID(), this);
		}
	}
}
