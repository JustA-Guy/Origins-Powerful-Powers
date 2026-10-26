package net.users.variables;

import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.HudRenderedVariableIntPower;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.apoli.util.HudRender;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.users.OriginsPowerfulPowers;

/**
 * origins-powerful-powers:variable_bar. An Apoli int power, like origins:resource, whose value is one
 * numeric variable. Apoli's HUD bar, the origins:resource condition, change_resource and
 * modify_resource all go through getValue()/setValue(), so they read and write that variable.
 *
 * min and max only set how full the HUD bar looks. Values aren't clamped, because the variable is the source of
 * truth: clamping would let "change_resource +1" on a variable above max drop it to max.
 */
public class VariableBarPower extends HudRenderedVariableIntPower {
	private final String variable;

	public VariableBarPower(PowerType<?> type, LivingEntity entity, HudRender hudRender, int min, int max, String variable) {
		super(type, entity, hudRender, min, min, max);
		this.variable = variable;
	}

	public static PowerFactory<VariableBarPower> createFactory() {
		return new PowerFactory<VariableBarPower>(
			OriginsPowerfulPowers.id("variable_bar"),
			new SerializableData()
				.add("variable", SerializableDataTypes.STRING)
				.add("min", SerializableDataTypes.INT)
				.add("max", SerializableDataTypes.INT)
				.add("hud_render", ApoliDataTypes.HUD_RENDER),
			data -> (type, entity) -> new VariableBarPower(
				type, entity, (HudRender) data.get("hud_render"), data.getInt("min"), data.getInt("max"), data.getString("variable"))
		);
	}

	public String variable() {
		return variable;
	}

	/** Server: live from the variable, saturated to the int range. Client: the value from the last sync (VariableIntPower#fromTag). */
	@Override
	public int getValue() {
		if (!entity.level().isClientSide()) {
			try {
				VariablesPower power = VariableStore.find(entity, variable);
				if (power != null && power.get(variable) instanceof Number number) {
					currentValue = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number.doubleValue()));
				}
			} catch (ScriptException e) {
				// Called from Apoli's HUD and resource code: never let the error escape into it.
				Script.warnOnce("variable_bar: " + e.getMessage());
			}
		}
		return currentValue;
	}

	@Override
	public int setValue(int value) {
		currentValue = value;
		if (!entity.level().isClientSide()) {
			VariableStore.writeFromBar(entity, variable, value);
		}
		return value;
	}

	/** The bar never draws past empty or full, even though the value can be outside [min, max]. */
	@Override
	public float getFill() {
		return Math.max(0.0F, Math.min(1.0F, super.getFill()));
	}

	/** Refresh from the variable first, so the synced value is current. */
	@Override
	public Tag toTag() {
		getValue();
		return super.toTag();
	}
}
