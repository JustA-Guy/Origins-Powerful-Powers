package net.users.power;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.users.mixin.CooldownInstanceAccessor;
import net.users.mixin.ItemCooldownsAccessor;

/**
 * Vanilla's per-item cooldowns (ender pearl, chorus fruit, shield after an axe hit, ...), kept in
 * Player#getCooldowns. In 1.20.1 they're keyed by a single Item: there are no cooldown groups.
 * Both sides keep one; the server sends each start and end to the client.
 */
public final class OppItemCooldowns {
	private OppItemCooldowns() {
	}

	/** Ticks until the item comes off cooldown, 0 when it isn't on one. */
	public static int ticksRemaining(Player player, Item item) {
		ItemCooldowns cooldowns = player.getCooldowns();
		Object instance = ((ItemCooldownsAccessor) cooldowns).opp$getCooldowns().get(item);
		if (instance == null) {
			return 0;
		}
		return Math.max(0, ((CooldownInstanceAccessor) instance).opp$getEndTime() - ((ItemCooldownsAccessor) cooldowns).opp$getTickCount());
	}

	/** The item field when it's set, otherwise whatever is in the slot. Null when the entity has no such slot. */
	public static Item resolve(Entity entity, Item item, VanillaSlot slot) {
		if (item != null) {
			return item;
		}
		ItemStack stack = slot.stack(entity);
		return stack == null ? null : stack.getItem();
	}

	/** ticks <= 0 removes the cooldown. Air can't hold one, so an empty slot does nothing. */
	public static void set(Player player, Item item, int ticks) {
		if (item == Items.AIR) {
			return;
		}
		if (ticks <= 0) {
			player.getCooldowns().removeCooldown(item);
		} else {
			player.getCooldowns().addCooldown(item, ticks);
		}
	}
}
