package net.users.variables;

import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.factory.action.ActionFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.users.OriginsPowerfulPowers;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Foreach loops over a origins-powerful-powers:list.
 *   for_each:      runs a code block per element; value is writable and writes back into the list.
 *   for_each_read: runs any Apoli action per element; value, index and size are readable inside it, the list can't change.
 * In both, the list can't grow or shrink during the loop, and index/size are read-only.
 */
public final class LoopActions {
	/** Either a list power's name (variable) or a global list (global, a Global.list(key) call). */
	private record ListRef(String source, Node.Var variable, Node global) {
	}

	private LoopActions() {
	}

	public static ActionFactory<Entity> forEach() {
		return new ActionFactory<>(
			OriginsPowerfulPowers.id("for_each"),
			new SerializableData().add("list", listRef(false)).add("code", Script.FOR_EACH_CODE),
			(data, entity) -> forEach((ListRef) data.get("list"), (Script) data.get("code"), entity, null)
		);
	}

	public static ActionFactory<Tuple<Entity, Entity>> bientityForEach() {
		return new ActionFactory<>(
			OriginsPowerfulPowers.id("for_each"),
			new SerializableData().add("list", listRef(true)).add("code", Script.BIENTITY_FOR_EACH_CODE),
			(data, pair) -> forEach((ListRef) data.get("list"), (Script) data.get("code"), pair.getA(), pair.getB())
		);
	}

	@SuppressWarnings("unchecked")
	public static ActionFactory<Entity> forEachRead() {
		return new ActionFactory<>(
			OriginsPowerfulPowers.id("for_each_read"),
			new SerializableData().add("list", listRef(false)).add("action", ApoliDataTypes.ENTITY_ACTION),
			(data, entity) -> {
				Consumer<Entity> action = (Consumer<Entity>) data.get("action");
				forEachRead((ListRef) data.get("list"), entity, null, () -> action.accept(entity));
			}
		);
	}

	@SuppressWarnings("unchecked")
	public static ActionFactory<Tuple<Entity, Entity>> bientityForEachRead() {
		return new ActionFactory<>(
			OriginsPowerfulPowers.id("for_each_read"),
			new SerializableData().add("list", listRef(true)).add("bientity_action", ApoliDataTypes.BIENTITY_ACTION),
			(data, pair) -> {
				Consumer<Tuple<Entity, Entity>> action = (Consumer<Tuple<Entity, Entity>>) data.get("bientity_action");
				forEachRead((ListRef) data.get("list"), pair.getA(), pair.getB(), () -> action.accept(pair));
			}
		);
	}

	private static SerializableDataType<ListRef> listRef(boolean bientity) {
		return Script.sourceType(ListRef.class, ListRef::source, source -> {
			Node global = Parser.globalListReference(source, bientity);
			if (global != null) {
				return new ListRef(source, null, global);
			}
			Node.Var variable = Parser.variableReference(source, bientity);
			if (variable.scope() == Node.Scope.LOOP) {
					throw new ScriptException("expected a list name, like history" + (bientity ? " or other.history" : "") + ", or Global.list(\"key\"), not " + source);
			}
			return new ListRef(source, variable, null);
		});
	}

	private static void forEach(ListRef ref, Script code, Entity self, Entity other) {
		ListSource list = resolve(ref, self, other, true);
		if (list != null) {
			// An error in the code stops the loop, like an exception would in Java.
			loop(list, false, () -> code.run(self, other));
		}
	}

	private static void forEachRead(ListRef ref, Entity self, Entity other, Runnable action) {
		ListSource list = resolve(ref, self, other, false);
		if (list != null) {
			loop(list, true, () -> {
				action.run();
				return true;
			});
		}
	}

	private static ListSource resolve(ListRef ref, Entity self, Entity other, boolean writes) {
		if (ref.global() != null) {
			return resolveGlobal(ref, self, other, writes);
		}
		boolean onSelf = ref.variable().scope() == Node.Scope.SELF;
		Entity owner = onSelf ? self : other;
		try {
			ListSource list = owner == null ? null : VariableStore.findList(owner, ref.variable().name());
			if (list == null) {
				Script.warnOnce("cannot find list " + ref.source() + " on " + (onSelf ? "this entity" : "the other entity")
					+ " (no origins-powerful-powers:list power it holds declares it)");
			}
			return list;
		} catch (ScriptException e) {
			Script.warnOnce(e.getMessage());
			return null;
		}
	}

	/** A global list as a ListSource, so the loop below saves it when it changed. for_each can write, so a locked key is checked first. */
	private static ListSource resolveGlobal(ListRef ref, Entity self, Entity other, boolean writes) {
		try {
			ListData data = (ListData) ref.global().eval(new EntityContext(self, other));
			GlobalStore store = GlobalStore.of(self);
			if (writes) {
				store.checkListWritable(data, self);
			}
			return new ListSource() {
				@Override
				public String listName() {
					return data.name();
				}

				@Override
				public ListData data() {
					return data;
				}

				@Override
				public void syncIfDirty() {
					if (data.takeDirty()) {
						store.listChanged(data);
					}
				}
			};
		} catch (ScriptException e) {
			Script.warnOnce(e.getMessage() + " in: " + ref.source());
			return null;
		}
	}

	/** A computed list (power_list) is snapshotted once here, so the loop sees the powers held when it started. */
	private static void loop(ListSource power, boolean readOnly, BooleanSupplier body) {
		ListData list = power.data();
		list.beginLoop(readOnly);
		try {
			// The size can't change mid-loop: add/remove/clear throw while a loop is going through the list.
			for (int i = 0; i < list.size(); i++) {
				LoopStack.push(new LoopStack.Frame(list, i, readOnly));
				try {
					if (!body.getAsBoolean()) {
						break;
					}
				} finally {
					LoopStack.pop();
				}
			}
		} finally {
			list.endLoop(readOnly);
			power.syncIfDirty();
		}
	}
}
