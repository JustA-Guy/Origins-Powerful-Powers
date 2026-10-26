package net.users.power;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Set;

/**
 * Turns entities for set_rotation and look_at. The client decides a player's rotation, so players get a
 * teleport packet with x/y/z marked relative (offset 0): only the view changes. Mobs need their head and
 * body set as well, or they snap back to the old facing. Their AI may still turn them away the next tick.
 */
public final class OppRotation {
	private static final Set<RelativeMovement> KEEP_POSITION = EnumSet.of(RelativeMovement.X, RelativeMovement.Y, RelativeMovement.Z);

	private OppRotation() {
	}

	/**
	 * Turns the entity toward yaw/pitch (either may be null to leave that axis alone). maxStep, if not null,
	 * caps how far each axis turns in this call. Nothing happens when both remaining turns are below minStep.
	 */
	public static void turn(Entity entity, Float yaw, Float pitch, Float maxStep, float minStep) {
		if (entity.level().isClientSide()) {
			return;
		}
		float yawTurn = yaw == null ? 0.0F : Mth.wrapDegrees(yaw - entity.getYRot());
		float pitchTurn = pitch == null ? 0.0F : Mth.clamp(pitch, -90.0F, 90.0F) - entity.getXRot();
		if (Math.abs(yawTurn) < minStep && Math.abs(pitchTurn) < minStep || yawTurn == 0.0F && pitchTurn == 0.0F) {
			return;
		}
		if (maxStep != null) {
			float step = Math.max(0.0F, maxStep);
			yawTurn = Mth.clamp(yawTurn, -step, step);
			pitchTurn = Mth.clamp(pitchTurn, -step, step);
		}
		apply(entity, entity.getYRot() + yawTurn, Mth.clamp(entity.getXRot() + pitchTurn, -90.0F, 90.0F));
	}

	/** The yaw and pitch that point the entity's eyes at target, as {yaw, pitch}. */
	public static float[] anglesTo(Entity entity, Vec3 target) {
		Vec3 eyes = entity.getEyePosition();
		double dx = target.x - eyes.x;
		double dy = target.y - eyes.y;
		double dz = target.z - eyes.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F);
		float pitch = Mth.wrapDegrees((float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG));
		return new float[] {yaw, pitch};
	}

	private static void apply(Entity entity, float yaw, float pitch) {
		if (entity instanceof ServerPlayer player) {
			player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, pitch, KEEP_POSITION);
			player.setYHeadRot(yaw);
			return;
		}
		entity.setYRot(yaw);
		entity.setXRot(pitch);
		if (entity instanceof LivingEntity living) {
			living.setYHeadRot(yaw);
			living.setYBodyRot(yaw);
		}
	}
}
