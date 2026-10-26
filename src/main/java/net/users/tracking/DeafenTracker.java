package net.users.tracking;

import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forced deafen. OppVoicechatPlugin cancels every sound packet event addressed to a deafened player, which covers
 * proximity and group audio. The broadcast relay skips those events, so it checks this set itself.
 */
public final class DeafenTracker {
	private static final Set<UUID> DEAFENED = ConcurrentHashMap.newKeySet();

	private DeafenTracker() {
	}

	public static void deafen(Entity entity) {
		DEAFENED.add(entity.getUUID());
	}

	public static void undeafen(Entity entity) {
		DEAFENED.remove(entity.getUUID());
	}

	public static boolean isDeafened(UUID uuid) {
		return DEAFENED.contains(uuid);
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		DEAFENED.remove(id);
	}
}
