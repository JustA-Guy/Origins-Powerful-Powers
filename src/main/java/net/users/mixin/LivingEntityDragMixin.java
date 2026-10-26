package net.users.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.phys.Vec3;
import net.users.power.OppAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the DRAG attribute in for vanilla's 0.98 fall drag. Gravity is left alone, so jump height, elytra, water,
 * lava and levitation stay vanilla.
 *
 * travel() handles every movement mode, and only plain falling through open air applies (y - 0.08) * 0.98. This
 * records y at HEAD and, at TAIL, checks whether the new y matches that formula. A match means an ordinary fall tick
 * (no landing, climbing, fluids, elytra or slow falling), and the step is redone with DRAG. A mismatch, or an entity
 * that was moving upward, keeps vanilla's result.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDragMixin {
	private static final double GRAVITY = 0.08;
	private static final double VANILLA_DRAG = 0.98;

	@Unique
	private double opp$preTravelY;

	@Inject(method = "travel", at = @At("HEAD"))
	private void opp$captureFallStart(Vec3 travelVector, CallbackInfo ci) {
		this.opp$preTravelY = ((LivingEntity) (Object) this).getDeltaMovement().y;
	}

	@Inject(method = "travel", at = @At("TAIL"))
	private void opp$applyDrag(Vec3 travelVector, CallbackInfo ci) {
		double qOld = this.opp$preTravelY;
		if (qOld > 0.0) {
			return;
		}

		LivingEntity self = (LivingEntity) (Object) this;
		AttributeInstance attribute = self.getAttribute(OppAttributes.DRAG);
		if (attribute == null) {
			return;
		}

		double drag = attribute.getValue();
		if (Math.abs(drag - VANILLA_DRAG) < 1.0E-9) {
			return;
		}

		double expectedVanillaY = (qOld - GRAVITY) * 0.98F;
		Vec3 delta = self.getDeltaMovement();
		if (Math.abs(delta.y - expectedVanillaY) > 1.0E-6) {
			return;
		}

		double newY = (qOld - GRAVITY) * drag;
		self.setDeltaMovement(delta.x, newY, delta.z);
	}
}
