package net.users.mixin;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Values are ItemCooldowns$CooldownInstance, which is package-private; read them with CooldownInstanceAccessor. */
@Mixin(ItemCooldowns.class)
public interface ItemCooldownsAccessor {
	@Accessor("cooldowns")
	Map<Item, ?> opp$getCooldowns();

	@Accessor("tickCount")
	int opp$getTickCount();
}
