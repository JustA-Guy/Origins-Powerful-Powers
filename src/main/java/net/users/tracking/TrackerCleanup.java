package net.users.tracking;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.users.variables.GlobalStore;

import java.util.UUID;

/**
 * Keeps the UUID-keyed tracker maps from growing forever. A non-player entity's entries are dropped when it's killed,
 * discarded or unloaded with its chunk. A dimension change keeps them, since the entity comes back with the same UUID.
 * A player's entries survive logouts and deaths and are dropped when PlayerExpiry finds them offline past the
 * oppPlayerExpiryDays gamerule. Global store keys bound to the entity are released at the same moments, except that a
 * chunk unload keeps them.
 */
public final class TrackerCleanup {
	private TrackerCleanup() {
	}

	public static void register() {
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Player) {
				return;
			}
			Entity.RemovalReason reason = entity.getRemovalReason();
			if (reason != Entity.RemovalReason.CHANGED_DIMENSION) {
				forget(entity.getUUID());
			}
			// Global keys stay bound through chunk unloads; only an entity that's gone for good releases them.
			if (reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED) {
				GlobalStore.get(level.getServer()).entityGone(entity.getUUID());
				ChunkLoadTracker.entityGone(entity.getUUID());
			}
		});
		PlayerExpiry.onExpired((server, id) -> {
			forget(id);
			GlobalStore.get(server).entityGone(id);
		});
	}

	public static void forget(UUID id) {
		AttackReachTracker.forget(id);
		BroadcastTracker.forget(id);
		CritTracker.forget(id);
		CrouchDepthTracker.forget(id);
		DamageHistoryTracker.forget(id);
		DeafenTracker.forget(id);
		KnockbackTracker.forget(id);
		LastDamageTracker.forget(id);
		MealTracker.forget(id);
		MovementStateTracker.forget(id);
		MuteTracker.forget(id);
		VibrationCooldownTracker.forget(id);
		VoiceChatTracker.forget(id);
	}
}
