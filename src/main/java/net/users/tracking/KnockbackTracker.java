package net.users.tracking;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class KnockbackTracker {
	private static final Map<UUID, Double> LAST_STRENGTH = new ConcurrentHashMap<>();

	private KnockbackTracker() {
	}

	public static void record(Entity entity, double strength) {
		LAST_STRENGTH.put(entity.getUUID(), strength);
	}

	public static double get(Entity entity) {
		return LAST_STRENGTH.getOrDefault(entity.getUUID(), 0.0);
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		LAST_STRENGTH.remove(id);
	}
}
