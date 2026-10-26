package net.users.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.users.tracking.FakeEntityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a fake entity ticks (FakeEntityTracker.TICKING, server thread only), nothing it does may reach the real
 * world or other players: no new entities (drops, projectiles, conversions), sounds, level events or game
 * events (sculk vibrations).
 */
@Mixin(ServerLevel.class)
public abstract class FakeEntityGuardServerLevelMixin {
	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntitySpawns(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (FakeEntityTracker.TICKING) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
		at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntitySoundAt(Player except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
			float volume, float pitch, long seed, CallbackInfo ci) {
		if (FakeEntityTracker.TICKING) {
			ci.cancel();
		}
	}

	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
		at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntitySoundFrom(Player except, Entity entity, Holder<SoundEvent> sound, SoundSource source,
			float volume, float pitch, long seed, CallbackInfo ci) {
		if (FakeEntityTracker.TICKING) {
			ci.cancel();
		}
	}

	@Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntityLevelEvent(Player except, int type, BlockPos pos, int data, CallbackInfo ci) {
		if (FakeEntityTracker.TICKING) {
			ci.cancel();
		}
	}

	@Inject(method = "gameEvent", at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntityGameEvent(GameEvent event, Vec3 pos, GameEvent.Context context, CallbackInfo ci) {
		if (FakeEntityTracker.TICKING) {
			ci.cancel();
		}
	}
}
