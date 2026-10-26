package net.users.power;

import io.github.apace100.calio.data.SerializableDataType;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A vanilla slot by the names /item uses: weapon.mainhand, weapon.offhand, armor.head/chest/legs/feet,
 * hotbar.0-8, inventory.0-26 and enderchest.0-26. The short forms (mainhand, head, ...) work too.
 * Shared by Item.id / Item.count / Item.cooldown and the item cooldown condition and action.
 *
 * @param equipment set for the weapon and armor slots; otherwise group and index are set
 */
public record VanillaSlot(String name, EquipmentSlot equipment, String group, int index) {
	public static final VanillaSlot MAINHAND = parse("weapon.mainhand");
	/** A "slot" field in power JSON. A bad name fails once, when the datapack loads. */
	public static final SerializableDataType<VanillaSlot> DATA_TYPE =
		SerializableDataType.wrap(VanillaSlot.class, SerializableDataTypes.STRING, VanillaSlot::name, VanillaSlot::parse);

	/** Throws IllegalArgumentException, with a message for pack authors, for a name that isn't a slot. */
	public static VanillaSlot parse(String slotName) {
		String name = slotName.trim();
		EquipmentSlot equipment = switch (name) {
			case "weapon", "weapon.mainhand", "mainhand" -> EquipmentSlot.MAINHAND;
			case "weapon.offhand", "offhand" -> EquipmentSlot.OFFHAND;
			case "armor.head", "head" -> EquipmentSlot.HEAD;
			case "armor.chest", "chest" -> EquipmentSlot.CHEST;
			case "armor.legs", "legs" -> EquipmentSlot.LEGS;
			case "armor.feet", "feet" -> EquipmentSlot.FEET;
			default -> null;
		};
		if (equipment != null) {
			return new VanillaSlot(name, equipment, null, -1);
		}
		int dot = name.indexOf('.');
		String group = dot < 0 ? name : name.substring(0, dot);
		if (!group.equals("hotbar") && !group.equals("inventory") && !group.equals("enderchest")) {
			throw new IllegalArgumentException("unknown slot '" + slotName + "'; use weapon.mainhand, weapon.offhand, "
				+ "armor.head, armor.chest, armor.legs, armor.feet, hotbar.0-8, inventory.0-26 or enderchest.0-26");
		}
		int index;
		try {
			index = Integer.parseInt(name.substring(dot + 1));
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("'" + slotName + "' needs a slot number, like " + group + ".0");
		}
		int size = group.equals("hotbar") ? 9 : 27;
		if (index < 0 || index >= size) {
			throw new IllegalArgumentException(slotName + " is outside " + group + ".0 to " + group + "." + (size - 1));
		}
		return new VanillaSlot(name, null, group, index);
	}

	/** The stack in this slot, or null when the entity has no such slot (armor on a non-living entity, hotbar on a mob). */
	public ItemStack stack(Entity entity) {
		if (equipment != null) {
			return entity instanceof LivingEntity living ? living.getItemBySlot(equipment) : null;
		}
		if (!(entity instanceof Player player)) {
			return null;
		}
		return group.equals("enderchest") ? player.getEnderChestInventory().getItem(index)
			: player.getInventory().getItem(group.equals("hotbar") ? index : index + 9);
	}
}
