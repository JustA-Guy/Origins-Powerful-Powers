package net.users.tracking;

import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forced mute, separate from the player's own mic toggle. OppVoicechatPlugin cancels their MicrophonePacketEvent,
 * which stops the audio before it reaches anyone by proximity, group or broadcast.
 */
public final class MuteTracker {
	private static final Set<UUID> MUTED = ConcurrentHashMap.newKeySet();

	private MuteTracker() {
	}

	public static void mute(Entity entity) {
		MUTED.add(entity.getUUID());
	}

	public static void unmute(Entity entity) {
		MUTED.remove(entity.getUUID());
	}

	public static boolean isMuted(UUID uuid) {
		return MUTED.contains(uuid);
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		MUTED.remove(id);
	}
}
