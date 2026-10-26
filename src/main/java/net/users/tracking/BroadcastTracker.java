package net.users.tracking;

import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Who is broadcasting their voice, each with a (speaker, listener) predicate for who may hear it. start_broadcast_voice
 * passes its bientity condition straight in, and /oppvc passes a lambda. Plain data with no SVC types, so it loads
 * without SVC; OppVoicechatPlugin relays the audio.
 */
public final class BroadcastTracker {
	private record Entry(Predicate<Tuple<Entity, Entity>> listenerFilter, long expiryGameTime) {
	}

	private static final Map<UUID, Entry> ACTIVE = new ConcurrentHashMap<>();

	private BroadcastTracker() {
	}

	public static void start(Entity broadcaster, Predicate<Tuple<Entity, Entity>> listenerFilter, long expiryGameTime) {
		ACTIVE.put(broadcaster.getUUID(), new Entry(listenerFilter, expiryGameTime));
	}

	public static void stop(Entity broadcaster) {
		ACTIVE.remove(broadcaster.getUUID());
	}

	public static boolean isActive(UUID broadcaster) {
		return ACTIVE.containsKey(broadcaster);
	}

	public static Predicate<Tuple<Entity, Entity>> getListenerFilter(UUID broadcaster) {
		Entry entry = ACTIVE.get(broadcaster);
		return entry == null ? null : entry.listenerFilter();
	}

	/** Drops any broadcasts whose duration has elapsed. Safe to call every tick regardless of SVC. */
	public static void prune(long currentGameTime) {
		ACTIVE.values().removeIf(entry -> currentGameTime >= entry.expiryGameTime());
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		ACTIVE.remove(id);
	}
}
