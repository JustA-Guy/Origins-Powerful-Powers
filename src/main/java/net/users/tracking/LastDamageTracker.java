package net.users.tracking;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LastDamageTracker {
	private record Record(float amount, String msgId) {
	}

	private static final Map<UUID, Record> LAST = new ConcurrentHashMap<>();

	private LastDamageTracker() {
	}

	public static void record(Entity entity, DamageSource source, float amount) {
		LAST.put(entity.getUUID(), new Record(amount, source.getMsgId()));
	}

	public static float getAmount(Entity entity) {
		Record record = LAST.get(entity.getUUID());
		return record == null ? 0.0F : record.amount();
	}

	public static String getType(Entity entity) {
		Record record = LAST.get(entity.getUUID());
		return record == null ? "" : record.msgId();
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		LAST.remove(id);
	}
}
