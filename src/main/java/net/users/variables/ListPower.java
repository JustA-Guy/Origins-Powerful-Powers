package net.users.variables;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.users.OriginsPowerfulPowers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * origins-powerful-powers:list. One typed, resizable list per holder, used from code as name.get(i),
 * name.size(), name.add(v)... and looped over with for_each / for_each_read. Saved with the powers.
 */
public class ListPower extends Power implements ListSource {
	/** initial as written (sent to clients) and its elements as literals, converted to element_type when the power is created. */
	private record Initial(String json, List<Object> values) {
	}

	private static final int DEFAULT_MAX_SIZE = 1024;
	private static final int MAX_SOURCE_LENGTH = 262_144;
	/** A list's "variable": a plain name. Shared with PowerListPower. */
	static final SerializableDataType<String> NAME = Script.sourceType(String.class, name -> name, ListPower::checkName);
	private static final SerializableDataType<Initial> INITIAL = new SerializableDataType<>(
		Initial.class,
		(buf, initial) -> buf.writeUtf(initial.json(), MAX_SOURCE_LENGTH),
		buf -> readInitial(JsonParser.parseString(buf.readUtf(MAX_SOURCE_LENGTH))),
		ListPower::readInitial
	);
	private static final SerializableDataType<VarType> ELEMENT_TYPE;
	private static final SerializableDataType<Boolean> WHEN_FULL;

	static {
		BiMap<String, VarType> types = HashBiMap.create();
		for (VarType type : VarType.values()) {
			types.put(type.keyword(), type);
		}
		ELEMENT_TYPE = SerializableDataType.mapped(VarType.class, types);
		BiMap<String, Boolean> whenFull = HashBiMap.create();
		whenFull.put("error", Boolean.FALSE);
		whenFull.put("drop_oldest", Boolean.TRUE);
		WHEN_FULL = SerializableDataType.mapped(Boolean.class, whenFull);
	}

	private final ListData data;

	/**
	 * Apoli builds powers when they're granted, so initial elements that don't fit element_type are only found here.
	 * They're skipped with a warning, and the grant still goes through.
	 */
	public ListPower(PowerType<?> type, LivingEntity entity, String name, VarType elementType, int maxSize, boolean dropOldest, List<Object> initial) {
		super(type, entity);
		int size = Math.max(1, maxSize);
		List<Object> converted = new ArrayList<>();
		for (Object value : initial) {
			if (converted.size() >= size) {
				Script.warnOnce("list " + name + ": initial has more elements than max_size " + size + "; the rest are dropped");
				break;
			}
			try {
				converted.add(Ops.assign(value, elementType, "an element of " + name));
			} catch (ScriptException e) {
				Script.warnOnce("list " + name + ": skipping initial element " + value + ": " + e.getMessage());
			}
		}
		this.data = new ListData(name, elementType, size, dropOldest, converted);
	}

	public static PowerFactory<ListPower> createFactory() {
		return new PowerFactory<ListPower>(
			OriginsPowerfulPowers.id("list"),
			new SerializableData()
				.add("variable", NAME)
				.add("element_type", ELEMENT_TYPE)
				.add("initial", INITIAL, new Initial("[]", List.of()))
				.add("max_size", SerializableDataTypes.INT, DEFAULT_MAX_SIZE)
				.add("when_full", WHEN_FULL, Boolean.FALSE),
			data -> (type, entity) -> new ListPower(
				type, entity, (String) data.get("variable"), (VarType) data.get("element_type"),
				data.getInt("max_size"), (Boolean) data.get("when_full"), ((Initial) data.get("initial")).values())
		);
	}

	@Override
	public String listName() {
		return data.name();
	}

	@Override
	public ListData data() {
		return data;
	}

	/** Push the list to the client once after it changed. */
	@Override
	public void syncIfDirty() {
		if (data.takeDirty() && !entity.level().isClientSide()) {
			PowerHolderComponent.syncPower(entity, getType());
		}
	}

	@Override
	public Tag toTag() {
		ListTag tag = new ListTag();
		for (Object value : data.values()) {
			tag.add(VariablesPower.toTag(data.elementType(), value));
		}
		return tag;
	}

	@Override
	public void fromTag(Tag tag) {
		if (!(tag instanceof ListTag list)) {
			return;
		}
		List<Object> loaded = new ArrayList<>();
		for (Tag element : list) {
			Object value = VariablesPower.fromTag(element, data.elementType());
			if (value != null) {
				loaded.add(value);
			}
		}
		data.load(loaded);
	}

	private static String checkName(String source) {
		Node.Var variable = Parser.variableReference(source, false);
		if (variable.scope() != Node.Scope.SELF || !variable.name().equals(source.trim())) {
			throw new ScriptException("a list's variable must be a plain name like history, not " + source);
		}
		return variable.name();
	}

	/** Numbers follow Java literal rules (1 is an int, 1.5 a double), so element_type conversion works like assignment. */
	private static Initial readInitial(JsonElement json) {
		if (!json.isJsonArray()) {
			throw new JsonSyntaxException("expected an array of elements, like [1, 2, 3], found " + json);
		}
		List<Object> values = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray()) {
			if (!element.isJsonPrimitive()) {
				throw new JsonSyntaxException("list elements must be numbers, booleans or strings, found " + element);
			}
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if (primitive.isString()) {
				values.add(primitive.getAsString());
			} else if (primitive.isBoolean()) {
				values.add(Boolean.valueOf(primitive.getAsBoolean()));
			} else {
				values.add(Parser.valueExpression(primitive.getAsString(), false).eval(new Context.Initializers(Map.of())));
			}
		}
		return new Initial(json.toString(), List.copyOf(values));
	}
}
