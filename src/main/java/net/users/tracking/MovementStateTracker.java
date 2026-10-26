package net.users.tracking;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counters vanilla doesn't keep: ticks since the player last touched the ground, and ticks sprinting without a break.
 */
public final class MovementStateTracker {
	private static final Map<UUID, Integer> AIRBORNE_TICKS = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> SPRINT_TICKS = new ConcurrentHashMap<>();

	private MovementStateTracker() {
	}

	public static void tick(Player player) {
		UUID id = player.getUUID();
		AIRBORNE_TICKS.put(id, player.onGround() ? 0 : AIRBORNE_TICKS.getOrDefault(id, 0) + 1);
		SPRINT_TICKS.put(id, player.isSprinting() ? SPRINT_TICKS.getOrDefault(id, 0) + 1 : 0);
	}

	public static int getAirborneTicks(Player player) {
		return AIRBORNE_TICKS.getOrDefault(player.getUUID(), 0);
	}

	public static int getSprintTicks(Player player) {
		return SPRINT_TICKS.getOrDefault(player.getUUID(), 0);
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		AIRBORNE_TICKS.remove(id);
		SPRINT_TICKS.remove(id);
	}
}
