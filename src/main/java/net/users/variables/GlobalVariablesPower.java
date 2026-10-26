package net.users.variables;

import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.world.entity.LivingEntity;
import net.users.OriginsPowerfulPowers;

/**
 * origins-powerful-powers:global_variables. Declares fields like origins-powerful-powers:variables, but the values live in the
 * global store: every entity holding the power (or any power declaring the same key) shares one copy. The key for
 * field name in power mypack:x is mypack:name. With "lock": true, a key this power creates is locked to its namespace.
 */
public class GlobalVariablesPower extends Power {
	private static final SerializableDataType<FieldList> FIELDS = Script.sourceType(FieldList.class, FieldList::source, FieldList::parse);

	private final FieldList fields;
	private final boolean lock;

	public GlobalVariablesPower(PowerType<?> type, LivingEntity entity, FieldList fields, boolean lock) {
		super(type, entity);
		this.fields = fields;
		this.lock = lock;
	}

	public static PowerFactory<GlobalVariablesPower> createFactory() {
		return new PowerFactory<GlobalVariablesPower>(
			OriginsPowerfulPowers.id("global_variables"),
			new SerializableData()
				.add("fields", FIELDS)
				.add("lock", SerializableDataTypes.BOOLEAN, false),
			data -> (type, entity) -> new GlobalVariablesPower(type, entity, (FieldList) data.get("fields"), data.getBoolean("lock"))
		);
	}

	public boolean declares(String name) {
		return fields.get(name) != null;
	}

	/** The store key for a declared name, creating it with its initial value if it doesn't exist yet. */
	String key(GlobalStore store, String name) {
		String namespace = getType().getIdentifier().getNamespace();
		String key = namespace + ":" + name.toLowerCase(java.util.Locale.ROOT);
		FieldDecl field = fields.get(name);
		store.declare(key, field.type(), field.value(), lock ? namespace : null);
		return key;
	}

	VarType typeOf(String name) {
		return fields.get(name).type();
	}
}
