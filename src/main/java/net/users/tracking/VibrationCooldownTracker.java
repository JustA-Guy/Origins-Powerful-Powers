package net.users.tracking;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-entity sculk vibration cooldown. Vanilla only keeps one per listening block entity. Powers can read it to limit
 * how often an entity makes a detectable vibration, and actions can set it.
 */
public final class VibrationCooldownTracker {
	private static final Map<UUID, Integer> COOLDOWN = new ConcurrentHashMap<>();

	private VibrationCooldownTracker() {
	}

	public static void set(Entity entity, int ticks) {
		COOLDOWN.put(entity.getUUID(), Math.max(0, ticks));
	}

	public static int get(Entity entity) {
		return COOLDOWN.getOrDefault(entity.getUUID(), 0);
	}

	public static void tickDown() {
		COOLDOWN.replaceAll((id, ticks) -> Math.max(0, ticks - 1));
		COOLDOWN.values().removeIf(ticks -> ticks <= 0);
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		COOLDOWN.remove(id);
	}
}
