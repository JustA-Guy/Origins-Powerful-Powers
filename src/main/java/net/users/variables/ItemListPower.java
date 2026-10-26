package net.users.variables;

import io.github.apace100.apoli.power.InventoryPower;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.PowerTypeRegistry;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.users.OriginsPowerfulPowers;

import java.util.ArrayList;
import java.util.List;

/**
 * origins-powerful-powers:item_list. A read-only list of the item ids in one of the holder's
 * origins:inventory powers, as they are at the moment code reads it (once per code run, once per
 * loop). One element per slot, so index == slot number, and an empty slot reads as minecraft:air.
 * With "counts": true the elements are the stack sizes instead. Nothing is stored or saved.
 */
public class ItemListPower extends Power implements ListSource {
	private final String name;
	private final ResourceLocation power;
	private final boolean counts;

	public ItemListPower(PowerType<?> type, LivingEntity entity, String name, ResourceLocation power, boolean counts) {
		super(type, entity);
		this.name = name;
		this.power = power;
		this.counts = counts;
	}

	public static PowerFactory<ItemListPower> createFactory() {
		return new PowerFactory<ItemListPower>(
			OriginsPowerfulPowers.id("item_list"),
			new SerializableData()
				.add("variable", ListPower.NAME)
				.add("power", SerializableDataTypes.IDENTIFIER)
				.add("counts", SerializableDataTypes.BOOLEAN, false),
			data -> (type, entity) -> new ItemListPower(
				type, entity, (String) data.get("variable"), (ResourceLocation) data.get("power"), data.getBoolean("counts"))
		);
	}

	@Override
	public String listName() {
		return name;
	}

	/** Reads the power's container now. A missing or wrong power gives an empty list, and the reason is logged once. */
	@Override
	public ListData data() {
		List<Object> values = new ArrayList<>();
		InventoryPower inventory = inventory();
		if (inventory != null) {
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				ItemStack stack = inventory.getItem(slot);
				values.add(counts ? Integer.valueOf(stack.getCount())
					: BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
			}
		}
		return ListData.readOnly(name, counts ? VarType.INT : VarType.STRING, values);
	}

	private InventoryPower inventory() {
		if (!PowerTypeRegistry.contains(power)) {
			Script.warnOnce("item_list " + name + ": unknown power " + power);
			return null;
		}
		Power held = PowerTypeRegistry.get(power).get(entity);
		if (held == null) {
			Script.warnOnce("item_list " + name + ": this entity doesn't have the power " + power);
			return null;
		}
		if (!(held instanceof InventoryPower found)) {
			Script.warnOnce("item_list " + name + ": " + power + " isn't an origins:inventory power");
			return null;
		}
		return found;
	}

	@Override
	public void syncIfDirty() {
		// Read straight from Apoli's own inventory power, which syncs itself; nothing of ours to send.
	}
}
