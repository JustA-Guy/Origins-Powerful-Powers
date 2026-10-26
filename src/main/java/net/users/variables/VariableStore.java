package net.users.variables;

import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.power.Power;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Finds an entity's variables and pushes changes to the client. */
public final class VariableStore {
	private VariableStore() {
	}

	/**
	 * The variables power on this entity that declares name, or null.
	 * Throws if two of its powers declare the same name: which one was meant is ambiguous.
	 */
	public static VariablesPower find(Entity entity, String name) {
		if (!(entity instanceof LivingEntity)) {
			return null;
		}
		PowerHolderComponent component = PowerHolderComponent.KEY.maybeGet(entity).orElse(null);
		if (component == null) {
			return null;
		}
		VariablesPower found = null;
		for (VariablesPower power : component.getPowers(VariablesPower.class, true)) {
			if (!power.declares(name)) {
				continue;
			}
			if (found != null) {
				// Sorted so the message is stable (Apoli's power order isn't) and warnOnce dedupes it.
				String a = found.getType().getIdentifier().toString();
				String b = power.getType().getIdentifier().toString();
				throw new ScriptException("variable " + name + " is declared by both " + (a.compareTo(b) < 0 ? a + " and " + b : b + " and " + a)
					+ "; a name must be unique across the variables powers one entity holds");
			}
			found = power;
		}
		return found;
	}

	/** The global_variables power on this entity that declares name, or null. Throws if two of its powers declare it. */
	public static GlobalVariablesPower findGlobal(Entity entity, String name) {
		if (!(entity instanceof LivingEntity)) {
			return null;
		}
		PowerHolderComponent component = PowerHolderComponent.KEY.maybeGet(entity).orElse(null);
		if (component == null) {
			return null;
		}
		GlobalVariablesPower found = null;
		for (GlobalVariablesPower power : component.getPowers(GlobalVariablesPower.class, true)) {
			if (!power.declares(name)) {
				continue;
			}
			if (found != null) {
				String a = found.getType().getIdentifier().toString();
				String b = power.getType().getIdentifier().toString();
				throw new ScriptException("variable " + name + " is declared by both " + (a.compareTo(b) < 0 ? a + " and " + b : b + " and " + a)
					+ "; a name must be unique across the variables powers one entity holds");
			}
			found = power;
		}
		return found;
	}

	/**
	 * The list or power_list power on this entity whose variable is name, or null.
	 * Throws if two of its powers use the same name.
	 */
	public static ListSource findList(Entity entity, String name) {
		if (!(entity instanceof LivingEntity)) {
			return null;
		}
		PowerHolderComponent component = PowerHolderComponent.KEY.maybeGet(entity).orElse(null);
		if (component == null) {
			return null;
		}
		Power found = null;
		for (Power power : component.getPowers(Power.class, true)) {
			if (!(power instanceof ListSource source) || !source.listName().equals(name)) {
				continue;
			}
			if (found != null) {
				String a = found.getType().getIdentifier().toString();
				String b = power.getType().getIdentifier().toString();
				throw new ScriptException("list " + name + " is declared by both " + (a.compareTo(b) < 0 ? a + " and " + b : b + " and " + a)
					+ "; a name must be unique across the variables and list powers one entity holds");
			}
			found = power;
		}
		return (ListSource) found;
	}

	/**
	 * Client copies matter for client-evaluated conditions (like a hud_render condition) and for bars.
	 * Only changed powers are synced, so code that runs every tick but changes nothing sends nothing.
	 */
	static void sync(Entity entity, Collection<VariablesPower> powers, Set<String> names) {
		if (entity.level().isClientSide()) {
			return;
		}
		for (VariablesPower power : powers) {
			PowerHolderComponent.syncPower(entity, power.getType());
		}
		PowerHolderComponent.KEY.maybeGet(entity).ifPresent(component -> {
			for (VariableBarPower bar : component.getPowers(VariableBarPower.class, true)) {
				if (names.contains(bar.variable())) {
					PowerHolderComponent.syncPower(entity, bar.getType());
				}
			}
		});
	}

	/** A variable_bar was written by Apoli (change_resource, modify_resource, /resource). */
	static void writeFromBar(Entity entity, String name, int value) {
		VariablesPower power;
		try {
			power = find(entity, name);
		} catch (ScriptException e) {
			Script.warnOnce("variable_bar: " + e.getMessage());
			return;
		}
		if (power == null || !power.typeOf(name).isNumeric()) {
			Script.warnOnce("variable_bar: no numeric variable '" + name + "' to write to, so the bar's new value isn't kept");
			return;
		}
		Object converted = Ops.convert(Integer.valueOf(value), power.typeOf(name));
		if (!converted.equals(power.get(name))) {
			power.set(name, converted);
			sync(entity, List.of(power), Set.of(name));
		}
	}
}
