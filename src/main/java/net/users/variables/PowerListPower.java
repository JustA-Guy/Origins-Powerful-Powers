package net.users.variables;

import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.users.OriginsPowerfulPowers;

import java.util.ArrayList;
import java.util.TreeSet;

/**
 * origins-powerful-powers:power_list. A read-only list of String power ids: the powers the holder has at
 * the moment code reads it (once per code run, once per loop). Sorted, so indexes are stable
 * between reads while the powers don't change. Nothing is stored or saved.
 */
public class PowerListPower extends Power implements ListSource {
	private final String name;
	private final boolean includeHidden;
	private final boolean onlyActive;
	private final String tag;
	private final ResourceLocation source;

	public PowerListPower(PowerType<?> type, LivingEntity entity, String name, boolean includeHidden, boolean onlyActive, String tag, ResourceLocation source) {
		super(type, entity);
		this.name = name;
		this.includeHidden = includeHidden;
		this.onlyActive = onlyActive;
		this.tag = tag;
		this.source = source;
	}

	public static PowerFactory<PowerListPower> createFactory() {
		return new PowerFactory<PowerListPower>(
			OriginsPowerfulPowers.id("power_list"),
			new SerializableData()
				.add("variable", ListPower.NAME)
				.add("include_hidden", SerializableDataTypes.BOOLEAN, false)
					.add("only_active", SerializableDataTypes.BOOLEAN, false)
				.add("tag", net.users.power.PowerTags.TAG_NAME, null)
				.add("source", SerializableDataTypes.IDENTIFIER, null),
			data -> (type, entity) -> new PowerListPower(
				type, entity, (String) data.get("variable"), data.getBoolean("include_hidden"),
				data.getBoolean("only_active"), data.getString("tag"), (ResourceLocation) data.get("source"))
		);
	}

	@Override
	public String listName() {
		return name;
	}

	/** Built fresh from the holder's powers. Sub-powers of origins:multiple are usually hidden, so the default lists what the player sees. */
	@Override
	public ListData data() {
		TreeSet<String> ids = new TreeSet<>();
		PowerHolderComponent.KEY.maybeGet(entity).ifPresent(component -> {
			for (Power power : component.getPowers()) {
				PowerType<?> type = power.getType();
				if (type.isHidden() && !includeHidden) {
					continue;
				}
				if (onlyActive && !power.isActive()) {
					continue;
				}
				if (tag != null && !net.users.power.PowerTags.matches(tag, type.getIdentifier())) {
					continue;
				}
				if (source != null && !component.getSources(type).contains(source)) {
					continue;
				}
				ids.add(type.getIdentifier().toString());
			}
		});
		return ListData.readOnly(name, VarType.STRING, new ArrayList<>(ids));
	}

	@Override
	public void syncIfDirty() {
		// Computed from Apoli's own (already synced) powers; nothing of ours to send.
	}
}
