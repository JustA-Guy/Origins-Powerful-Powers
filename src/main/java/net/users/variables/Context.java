package net.users.variables;

import java.util.Map;

/** Where running code reads and writes variables. Loop names (value, index, size) always go to the LoopStack. */
abstract class Context {
	abstract Object read(Node.Scope scope, String name);

	abstract VarType typeOf(Node.Scope scope, String name);

	abstract void write(Node.Scope scope, String name, Object value);

	/** The list with this name, or null if there's none (then the name is a plain variable). */
	ListData list(Node.Scope scope, String name) {
		return null;
	}

	/** Scoreboard.get: holder null means this entity. Throws if the objective or the score doesn't exist. */
	int score(String holder, String objective) {
		throw new ScriptException("scoreboards can't be read here");
	}

	/** Scoreboard.has: holder null means this entity. */
	boolean hasScore(String holder, String objective) {
		throw new ScriptException("scoreboards can't be read here");
	}

	/** Item.id(power, slot): the item in a slot of an origins:inventory power, like minecraft:spruce_log. */
	String itemId(Node.Scope scope, String power, int slot) {
		throw new ScriptException("item slots can't be read here");
	}

	/** Item.count(power, slot): how many items are in that slot. */
	int itemCount(Node.Scope scope, String power, int slot) {
		throw new ScriptException("item slots can't be read here");
	}

	/** Item.id(slot): the item in a vanilla slot, like weapon.mainhand or hotbar.0. */
	String slotItemId(Node.Scope scope, String slot) {
		throw new ScriptException("item slots can't be read here");
	}

	/** Item.count(slot): how many items are in that vanilla slot. */
	int slotItemCount(Node.Scope scope, String slot) {
		throw new ScriptException("item slots can't be read here");
	}

	/** Item.cooldown(itemOrSlot): vanilla cooldown ticks left on an item id or the item in a vanilla slot. */
	int itemCooldown(Node.Scope scope, String itemOrSlot) {
		throw new ScriptException("item cooldowns can't be read here");
	}

	/** Death.has() is a boolean, x/y/z ints, dimension a String, distance a double (-1 when unknown). */
	Object lastDeath(Node.Scope scope, String method) {
		throw new ScriptException("death locations can't be read here");
	}

	/**
	 * Global.has / get / set / delete / bind / lock / unlock / list. args are evaluated; bindScope is the entity for bind.
	 * bind, lock and unlock return null (void). list returns the ListData.
	 */
	Object global(String method, Object[] args, Node.Scope bindScope) {
		throw new ScriptException("global values can't be read here");
	}

	/** modify_global: key = value (op null) or key op= value. */
	Object globalAssign(String key, String op, Object value) {
		throw new ScriptException("global values can't be changed here");
	}

	/** A list method that changes a global list is about to run: throws if the key is locked against this entity. */
	void checkGlobalListWrite(ListData list) {
	}

	/** Stat.get: a vanilla statistic by its criterion name, like minecraft.custom:minecraft.jump. */
	int stat(Node.Scope scope, String name) {
		throw new ScriptException("statistics can't be read here");
	}

	final Object get(Node.Scope scope, String name) {
		return scope == Node.Scope.LOOP ? LoopStack.read(name) : read(scope, name);
	}

	final VarType type(Node.Scope scope, String name) {
		return scope == Node.Scope.LOOP ? LoopStack.typeOf(name) : typeOf(scope, name);
	}

	final void put(Node.Scope scope, String name, Object value) {
		if (scope == Node.Scope.LOOP) {
			LoopStack.write(name, value);
		} else {
			write(scope, name, value);
		}
	}

	/** Field initializers, evaluated at load: they can read fields declared earlier in the same power, nothing else. */
	static final class Initializers extends Context {
		private final Map<String, FieldDecl> declared;

		Initializers(Map<String, FieldDecl> declared) {
			this.declared = declared;
		}

		private FieldDecl field(Node.Scope scope, String name) {
			FieldDecl field = scope == Node.Scope.SELF ? declared.get(name) : null;
			if (field == null) {
				throw new ScriptException("cannot find variable " + name + " (an initializer can only use variables declared before it in the same power)");
			}
			return field;
		}

		@Override
		Object read(Node.Scope scope, String name) {
			return field(scope, name).value();
		}

		@Override
		VarType typeOf(Node.Scope scope, String name) {
			return field(scope, name).type();
		}

		@Override
		void write(Node.Scope scope, String name, Object value) {
			throw new IllegalStateException("initializers can't assign");
		}
	}
}
