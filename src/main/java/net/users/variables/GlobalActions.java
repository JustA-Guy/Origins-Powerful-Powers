package net.users.variables;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import io.github.apace100.apoli.power.factory.action.ActionFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.users.OriginsPowerfulPowers;

import java.util.List;

/**
 * The JSON forms of global store changes:
 *   origins-powerful-powers:modify_global  {"key": "mypack:score", "operation": "add", "value": "1", "bind": "self"}
 *   origins-powerful-powers:delete_global  {"key": "mypack:score"}
 * Both as entity and bientity actions. The value is an expression, parsed when the pack loads like modify_variable's.
 * The entity running the action (the actor) is the writer that a locked key checks.
 */
public final class GlobalActions {
	private record Value(String source, Node expression) {
	}

	private enum Bind {
		NONE, SELF, OTHER, BOTH
	}

	private static final SerializableDataType<ModifyOperation> OPERATION;
	private static final SerializableDataType<Bind> BIND = SerializableDataType.enumValue(Bind.class);

	static {
		BiMap<String, ModifyOperation> ids = HashBiMap.create();
		for (ModifyOperation operation : ModifyOperation.values()) {
			ids.put(operation.id(), operation);
		}
		OPERATION = SerializableDataType.mapped(ModifyOperation.class, ids);
	}

	private GlobalActions() {
	}

	public static ActionFactory<Entity> modifyEntity() {
		return new ActionFactory<>(OriginsPowerfulPowers.id("modify_global"), modifyData(false), (data, entity) -> modify(data, entity, null));
	}

	public static ActionFactory<Tuple<Entity, Entity>> modifyBientity() {
		return new ActionFactory<>(OriginsPowerfulPowers.id("modify_global"), modifyData(true), (data, pair) -> modify(data, pair.getA(), pair.getB()));
	}

	public static ActionFactory<Entity> deleteEntity() {
		return new ActionFactory<>(OriginsPowerfulPowers.id("delete_global"), deleteData(), (data, entity) -> delete(data, entity, null));
	}

	public static ActionFactory<Tuple<Entity, Entity>> deleteBientity() {
		return new ActionFactory<>(OriginsPowerfulPowers.id("delete_global"), deleteData(), (data, pair) -> delete(data, pair.getA(), pair.getB()));
	}

	private static SerializableData modifyData(boolean bientity) {
		return new SerializableData()
			.add("key", SerializableDataTypes.STRING)
			.add("operation", OPERATION, ModifyOperation.SET)
			.add("value", Script.sourceType(Value.class, Value::source, source -> new Value(source, Parser.valueExpression(source, bientity))))
			.add("bind", BIND, Bind.NONE);
	}

	private static SerializableData deleteData() {
		return new SerializableData().add("key", SerializableDataTypes.STRING);
	}

	private static void modify(SerializableData.Instance data, Entity self, Entity other) {
		String key = data.getString("key");
		ModifyOperation operation = (ModifyOperation) data.get("operation");
		Value value = (Value) data.get("value");
		List<Node.Scope> binds = switch ((Bind) data.get("bind")) {
			case NONE -> List.of();
			case SELF -> List.of(Node.Scope.SELF);
			case OTHER -> other == null ? List.of() : List.of(Node.Scope.OTHER);
			case BOTH -> other == null ? List.of(Node.Scope.SELF) : List.of(Node.Scope.SELF, Node.Scope.OTHER);
		};
		Script.runStatement(
			new Node.GlobalAssign(new Node.Literal(key), operation.op(), value.expression(), binds),
			operation.java("global " + key, value.source()) + " (modify_global)",
			self, other
		);
	}

	private static void delete(SerializableData.Instance data, Entity self, Entity other) {
		String key = data.getString("key");
		Script.runStatement(new Node.GlobalCall("delete", List.of(new Node.Literal(key)), null), "Global.delete(\"" + key + "\") (delete_global)", self, other);
	}
}
