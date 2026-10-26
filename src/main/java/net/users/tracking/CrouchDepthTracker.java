package net.users.tracking;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Approximates the 0-1 sneak transition, which vanilla only animates on the client, by stepping a value toward 0 or 1
 * every server tick.
 */
public final class CrouchDepthTracker {
	private static final float STEP = 1f / 3f;
	private static final Map<UUID, Float> DEPTH = new ConcurrentHashMap<>();

	private CrouchDepthTracker() {
	}

	public static void tick(LivingEntity entity) {
		float current = DEPTH.getOrDefault(entity.getUUID(), 0f);
		float target = entity.isCrouching() ? 1f : 0f;
		if (current < target) {
			current = Math.min(target, current + STEP);
		} else if (current > target) {
			current = Math.max(target, current - STEP);
		}
		DEPTH.put(entity.getUUID(), current);
	}

	public static float get(LivingEntity entity) {
		return DEPTH.getOrDefault(entity.getUUID(), 0f);
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		DEPTH.remove(id);
	}
}
