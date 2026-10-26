package net.users.tracking;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether the last hit an entity dealt, and the last damage it took, was a critical.
 *
 * Vanilla decides a melee crit inside Player#attack and only announces it (Player#crit) after the damage has been
 * applied, so both flags are settled once the whole attack has run: PlayerCritMixin brackets the attack,
 * LivingEntityHurtMixin clears the victim's flag as the damage starts, and Player#crit sets it. Arrow crits come
 * from ArrowCritMixin, which reads the arrow's own crit flag after it hits.
 *
 * Server-side and not saved, like the other trackers.
 */
public final class CritTracker {
	private static final Map<UUID, Boolean> DEALT = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> TAKEN = new ConcurrentHashMap<>();
	/** The attack being resolved right now, and whether vanilla called crit() during it. */
	private static final Map<UUID, Boolean> PENDING = new ConcurrentHashMap<>();

	private CritTracker() {
	}

	public static void beginAttack(Entity attacker) {
		PENDING.put(attacker.getUUID(), Boolean.FALSE);
	}

	/** Vanilla flagged this attack as a critical. The victim is updated now; the attacker when the attack finishes. */
	public static void markCrit(Entity attacker, Entity victim) {
		PENDING.put(attacker.getUUID(), Boolean.TRUE);
		if (victim != null) {
			TAKEN.put(victim.getUUID(), Boolean.TRUE);
		}
	}

	public static void endAttack(Entity attacker) {
		Boolean crit = PENDING.remove(attacker.getUUID());
		DEALT.put(attacker.getUUID(), crit != null && crit);
	}

	/** An arrow hit: the shooter dealt it and the target took it. */
	public static void recordArrowHit(Entity shooter, Entity victim, boolean crit) {
		if (shooter != null) {
			DEALT.put(shooter.getUUID(), crit);
		}
		if (victim != null) {
			TAKEN.put(victim.getUUID(), crit);
		}
	}

	/** Every hurt() starts as "not a critical"; Player#crit or an arrow hit sets it afterwards. */
	public static void recordTaken(Entity victim, boolean crit) {
		TAKEN.put(victim.getUUID(), crit);
	}

	public static boolean dealtCrit(Entity entity) {
		return DEALT.getOrDefault(entity.getUUID(), Boolean.FALSE);
	}

	public static boolean tookCrit(Entity entity) {
		return TAKEN.getOrDefault(entity.getUUID(), Boolean.FALSE);
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		DEALT.remove(id);
		TAKEN.remove(id);
		PENDING.remove(id);
	}
}
