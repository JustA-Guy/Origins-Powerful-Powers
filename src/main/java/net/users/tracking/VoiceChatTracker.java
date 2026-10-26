package net.users.tracking;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple Voice Chat connection and mic state, filled in by OppVoicechatPlugin. It holds no SVC types, so it loads
 * without SVC and just stays empty.
 */
public final class VoiceChatTracker {
	private static final Map<UUID, Long> LAST_MIC_PACKET_TIME = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> LAST_WHISPERING = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> INSTALLED = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> DISABLED = new ConcurrentHashMap<>();
	private static final Map<UUID, UUID> GROUP_ID = new ConcurrentHashMap<>();

	/** How recent a mic packet needs to be (in milliseconds) to count as "currently talking". */
	private static final long TALKING_WINDOW_MS = 750;

	private VoiceChatTracker() {
	}

	public static void onMicPacket(UUID player, boolean whispering) {
		LAST_MIC_PACKET_TIME.put(player, System.currentTimeMillis());
		LAST_WHISPERING.put(player, whispering);
	}

	public static void setConnectionState(UUID player, boolean installed, boolean disabled) {
		INSTALLED.put(player, installed);
		DISABLED.put(player, disabled);
	}

	public static void setGroup(UUID player, UUID groupId) {
		if (groupId == null) {
			GROUP_ID.remove(player);
		} else {
			GROUP_ID.put(player, groupId);
		}
	}

	public static boolean isTalking(UUID player) {
		Long last = LAST_MIC_PACKET_TIME.get(player);
		return last != null && System.currentTimeMillis() - last <= TALKING_WINDOW_MS;
	}

	public static boolean isWhispering(UUID player) {
		return isTalking(player) && LAST_WHISPERING.getOrDefault(player, false);
	}

	public static boolean isInstalled(UUID player) {
		return INSTALLED.getOrDefault(player, false);
	}

	public static boolean isMicDisabled(UUID player) {
		return DISABLED.getOrDefault(player, false);
	}

	public static UUID getGroup(UUID player) {
		return GROUP_ID.get(player);
	}

	public static boolean isInGroup(UUID player) {
		return GROUP_ID.containsKey(player);
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		LAST_MIC_PACKET_TIME.remove(id);
		LAST_WHISPERING.remove(id);
		INSTALLED.remove(id);
		DISABLED.remove(id);
		GROUP_ID.remove(id);
	}
}
