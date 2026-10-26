package net.users.variables;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import io.github.apace100.apoli.power.factory.action.ActionFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.users.OriginsPowerfulPowers;

/**
 * origins-powerful-powers:modify_variable, the JSON form of one assignment:
 *   {"variable": "coins", "operation": "add", "value": "coins / 100"}  is  coins += coins / 100;
 * Both halves are parsed when the pack loads, then run as that exact statement, so the type rules
 * and error handling are the same as for code strings.
 */
public final class ModifyVariableAction {
	private record Target(String source, Node.Var variable) {
	}

	private record Value(String source, Node expression) {
	}

	private static final SerializableDataType<ModifyOperation> OPERATION;

	static {
		BiMap<String, ModifyOperation> ids = HashBiMap.create();
		for (ModifyOperation operation : ModifyOperation.values()) {
			ids.put(operation.id(), operation);
		}
		OPERATION = SerializableDataType.mapped(ModifyOperation.class, ids);
	}

	private ModifyVariableAction() {
	}

	public static ActionFactory<Entity> createEntityFactory() {
		return new ActionFactory<>(OriginsPowerfulPowers.id("modify_variable"), data(false), (data, entity) -> run(data, entity, null));
	}

	public static ActionFactory<Tuple<Entity, Entity>> createBientityFactory() {
		return new ActionFactory<>(OriginsPowerfulPowers.id("modify_variable"), data(true), (data, pair) -> run(data, pair.getA(), pair.getB()));
	}

	private static SerializableData data(boolean bientity) {
		return new SerializableData()
			.add("variable", Script.sourceType(Target.class, Target::source,
				source -> new Target(source, Parser.variableReference(source, bientity))))
			.add("operation", OPERATION, ModifyOperation.ADD)
			.add("value", Script.sourceType(Value.class, Value::source,
				source -> new Value(source, Parser.valueExpression(source, bientity))));
	}

	private static void run(SerializableData.Instance data, Entity self, Entity other) {
		Target target = (Target) data.get("variable");
		ModifyOperation operation = (ModifyOperation) data.get("operation");
		Value value = (Value) data.get("value");
		Script.runStatement(
			new Node.Assign(target.variable(), operation.op(), value.expression()),
			operation.java(target.source(), value.source()) + " (modify_variable)",
			self, other
		);
	}
}
