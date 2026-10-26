package net.users.tracking;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AttackReachTracker {
	private static final Map<UUID, Double> LAST_REACH = new ConcurrentHashMap<>();

	private AttackReachTracker() {
	}

	public static void record(Entity entity, double reach) {
		LAST_REACH.put(entity.getUUID(), reach);
	}

	public static double get(Entity entity) {
		return LAST_REACH.getOrDefault(entity.getUUID(), 0.0);
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		LAST_REACH.remove(id);
	}
}
