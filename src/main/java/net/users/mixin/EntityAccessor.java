package net.users.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {
	/** The block whose friction LivingEntity#travel uses, half a block below the feet. Apoli reads the same position. */
	@Invoker("getBlockPosBelowThatAffectsMyMovement")
	BlockPos opp$getBlockPosBelowThatAffectsMyMovement();
}
