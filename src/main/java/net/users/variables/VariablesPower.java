package net.users.variables;

import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.users.OriginsPowerfulPowers;

import java.util.HashMap;
import java.util.Map;

/**
 * origins-powerful-powers:variables. Declares typed fields, like a Java class; every entity holding the
 * power is an instance with its own values, saved with the entity's other powers.
 */
public class VariablesPower extends Power {
	private static final SerializableDataType<FieldList> FIELDS = Script.sourceType(FieldList.class, FieldList::source, FieldList::parse);

	private final FieldList fields;
	private final Map<String, Object> values = new HashMap<>();

	public VariablesPower(PowerType<?> type, LivingEntity entity, FieldList fields) {
		super(type, entity);
		this.fields = fields;
		for (FieldDecl field : fields.all()) {
			values.put(field.name(), field.value());
		}
	}

	public static PowerFactory<VariablesPower> createFactory() {
		return new PowerFactory<VariablesPower>(
			OriginsPowerfulPowers.id("variables"),
			new SerializableData().add("fields", FIELDS),
			data -> (type, entity) -> new VariablesPower(type, entity, (FieldList) data.get("fields"))
		);
	}

	public boolean declares(String name) {
		return values.containsKey(name);
	}

	public VarType typeOf(String name) {
		return fields.get(name).type();
	}

	public Object get(String name) {
		return values.get(name);
	}

	/** value must already have the declared type (Ops.assign / Ops.compound guarantee that). */
	void set(String name, Object value) {
		values.put(name, value);
	}

	@Override
	public Tag toTag() {
		CompoundTag tag = new CompoundTag();
		for (FieldDecl field : fields.all()) {
			tag.put(field.name(), toTag(field.type(), values.get(field.name())));
		}
		return tag;
	}

	@Override
	public void fromTag(Tag tag) {
		if (!(tag instanceof CompoundTag compound)) {
			return;
		}
		for (FieldDecl field : fields.all()) {
			Object value = fromTag(compound.get(field.name()), field.type());
			if (value != null) {
				values.put(field.name(), value);
			}
		}
	}

	/** One value as NBT. Shared with ListPower. */
	static Tag toTag(VarType type, Object value) {
		switch (type) {
			case INT:
				return IntTag.valueOf((Integer) value);
			case LONG:
				return LongTag.valueOf((Long) value);
			case FLOAT:
				return FloatTag.valueOf((Float) value);
			case DOUBLE:
				return DoubleTag.valueOf((Double) value);
			case BOOLEAN:
				return ByteTag.valueOf((Boolean) value);
			default:
				return StringTag.valueOf((String) value);
		}
	}

	/**
	 * One value back from NBT, or null if it doesn't fit (then the default/initial value stays).
	 * Tolerates a pack changing a numeric type between saves: the stored number is cast.
	 */
	static Object fromTag(Tag stored, VarType type) {
		if (type == VarType.STRING) {
			return stored instanceof StringTag string ? string.getAsString() : null;
		}
		if (!(stored instanceof NumericTag number)) {
			return null;
		}
		switch (type) {
			case INT:
				return Integer.valueOf(number.getAsInt());
			case LONG:
				return Long.valueOf(number.getAsLong());
			case FLOAT:
				return Float.valueOf(number.getAsFloat());
			case DOUBLE:
				return Double.valueOf(number.getAsDouble());
			default:
				return Boolean.valueOf(number.getAsByte() != 0);
		}
	}
}
