package net.users.tracking;

import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A rolling log of each entity's hits (game time, amount, blocked), for the recent damage taken and blocked conditions.
 */
public final class DamageHistoryTracker {
	private static final int MAX_WINDOW_TICKS = 30 * 20;
	private static final Map<UUID, Deque<long[]>> HISTORY = new ConcurrentHashMap<>();

	private DamageHistoryTracker() {
	}

	public static void record(LivingEntity entity, float amount, boolean blocked, long gameTime) {
		Deque<long[]> log = HISTORY.computeIfAbsent(entity.getUUID(), id -> new ArrayDeque<>());
		// ArrayDeque isn't thread-safe; lock it in case something other than the server thread gets here.
		synchronized (log) {
			log.addLast(new long[]{gameTime, Float.floatToIntBits(amount), blocked ? 1 : 0});
			long[] oldest;
			while ((oldest = log.peekFirst()) != null && gameTime - oldest[0] > MAX_WINDOW_TICKS) {
				log.pollFirst();
			}
		}
	}

	/** Adds up the hits within the window that were blocked by a shield (blocked) or weren't (!blocked). */
	public static float sumWithin(LivingEntity entity, int windowTicks, long gameTime, boolean blocked) {
		Deque<long[]> log = HISTORY.get(entity.getUUID());
		if (log == null) {
			return 0f;
		}
		float sum = 0f;
		synchronized (log) {
			for (long[] entry : log) {
				if (gameTime - entry[0] <= windowTicks && (entry[2] == 1) == blocked) {
					sum += Float.intBitsToFloat((int) entry[1]);
				}
			}
		}
		return sum;
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		HISTORY.remove(id);
	}
}
