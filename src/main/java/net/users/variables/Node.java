package net.users.variables;

import java.util.List;

/** Parsed expression tree. Each node evaluates itself against a Context, following Java's evaluation order. */
interface Node {
	Object eval(Context context);

	/**
	 * Whose variable a name refers to: the holder/actor (self), in bientity code the target (other),
	 * or the innermost running loop (value, index, size).
	 */
	enum Scope {
		SELF, OTHER, LOOP
	}

	record Literal(Object value) implements Node {
		public Object eval(Context context) {
			return value;
		}
	}

	record Var(Scope scope, String name) implements Node {
		public Object eval(Context context) {
			return context.get(scope, name);
		}
	}

	/** op is null for plain '=', otherwise the binary operator of a compound assignment ("+" for "+="). */
	record Assign(Var target, String op, Node value) implements Node {
		public Object eval(Context context) {
			VarType type = context.type(target.scope(), target.name());
			Object result;
			if (op == null) {
				result = Ops.assign(value.eval(context), type, target.name());
			} else {
				Object current = context.get(target.scope(), target.name());
				result = Ops.compound(op, current, value.eval(context), type, target.name());
			}
			context.put(target.scope(), target.name(), result);
			return result;
		}
	}

	record IncDec(Var target, boolean increment, boolean prefix) implements Node {
		public Object eval(Context context) {
			VarType type = context.type(target.scope(), target.name());
			if (!type.isNumeric()) {
				throw new ScriptException("bad operand type " + type.keyword() + " for unary operator '" + (increment ? "++" : "--") + "'");
			}
			Object old = context.get(target.scope(), target.name());
			Object updated = Ops.convert(Ops.arithmetic(increment ? "+" : "-", old, 1), type);
			context.put(target.scope(), target.name(), updated);
			return prefix ? updated : old;
		}
	}

	record Unary(String op, Node operand) implements Node {
		public Object eval(Context context) {
			return Ops.unary(op, operand.eval(context));
		}
	}

	record Binary(String op, Node left, Node right) implements Node {
		public Object eval(Context context) {
			Object l = left.eval(context);
			return Ops.binary(op, l, right.eval(context));
		}
	}

	/** && and ||, which short-circuit. */
	record Logical(String op, Node left, Node right) implements Node {
		public Object eval(Context context) {
			boolean l = Ops.bool(left.eval(context), "operator '" + op + "'");
			if (op.equals("&&") ? !l : l) {
				return l;
			}
			return Ops.bool(right.eval(context), "operator '" + op + "'");
		}
	}

	record Ternary(Node condition, Node ifTrue, Node ifFalse) implements Node {
		public Object eval(Context context) {
			return Ops.bool(condition.eval(context), "the ?: condition") ? ifTrue.eval(context) : ifFalse.eval(context);
		}
	}

	record Cast(VarType type, Node operand) implements Node {
		public Object eval(Context context) {
			return Ops.cast(operand.eval(context), type);
		}
	}

	record StaticCall(String owner, String method, List<Node> args) implements Node {
		public Object eval(Context context) {
			return Library.callStatic(owner, method, evalAll(args, context));
		}
	}

	/** name.method(...): a list method when name is a list, otherwise a String method on the value. */
	record MethodCall(Node target, String method, List<Node> args) implements Node {
		public Object eval(Context context) {
			if (target instanceof GlobalCall global && global.method().equals("list")) {
				ListData list = (ListData) global.eval(context);
				if (Library.isListMutator(method)) {
					context.checkGlobalListWrite(list);
				}
				return Library.callList(list, method, evalAll(args, context));
			}
			if (target instanceof Var variable && variable.scope() != Scope.LOOP) {
				ListData list = context.list(variable.scope(), variable.name());
				if (list != null) {
					return Library.callList(list, method, evalAll(args, context));
				}
			}
			Object receiver = target.eval(context);
			return Library.callString(receiver, method, evalAll(args, context));
		}
	}

	/** Scoreboard.get / Scoreboard.has. With one argument the holder is this entity (self). */
	record ScoreCall(boolean has, List<Node> args) implements Node {
		public Object eval(Context context) {
			Object[] values = evalAll(args, context);
			String where = "Scoreboard." + (has ? "has" : "get");
			String holder = values.length == 2 ? stringArg(values[0], where) : null;
			String objective = stringArg(values[values.length - 1], where);
			return has ? Boolean.valueOf(context.hasScore(holder, objective)) : Integer.valueOf(context.score(holder, objective));
		}
	}

	/**
	 * Item.id / Item.count / Item.cooldown. One argument is a vanilla slot (weapon.mainhand), or for
	 * cooldown also an item id; two are an origins:inventory power and a slot number. scope picks whose
	 * inventory: self or, in bi-entity code, other.
	 */
	record ItemCall(Scope scope, String method, List<Node> args) implements Node {
		public Object eval(Context context) {
			Object[] values = evalAll(args, context);
			String where = "Item." + method;
			if (method.equals("cooldown")) {
				return Integer.valueOf(context.itemCooldown(scope, stringArg(values[0], where)));
			}
			boolean count = method.equals("count");
			if (values.length == 1) {
				String slot = stringArg(values[0], where);
				return count ? Integer.valueOf(context.slotItemCount(scope, slot)) : context.slotItemId(scope, slot);
			}
			String power = stringArg(values[0], where);
			int slot = slotArg(values[1], where);
			return count ? Integer.valueOf(context.itemCount(scope, power, slot)) : context.itemId(scope, power, slot);
		}
	}

	/** Death.has / x / y / z / dimension / distance: where the player last died. scope picks self or, in bi-entity code, other. */
	record DeathCall(Scope scope, String method) implements Node {
		public Object eval(Context context) {
			return context.lastDeath(scope, method);
		}
	}

	/** Global.has / get / set / delete / bind / lock / unlock / list(key[, type]). bindScope is the entity bind() names. */
	record GlobalCall(String method, List<Node> args, Scope bindScope) implements Node {
		public Object eval(Context context) {
			Object[] values = evalAll(args, context);
			String where = "Global." + method;
			values[0] = stringArg(values[0], where);
			if ((method.equals("lock") || method.equals("list")) && values.length > 1) {
				values[1] = stringArg(values[1], where);
			}
			return context.global(method, values, bindScope);
		}
	}

	/** modify_global: key = value, or key op= value, then bind to each scope in binds. */
	record GlobalAssign(Node key, String op, Node value, List<Scope> binds) implements Node {
		public Object eval(Context context) {
			String k = stringArg(key.eval(context), "modify_global");
			Object result = context.globalAssign(k, op, value.eval(context));
			for (Scope scope : binds) {
				context.global("bind", new Object[] {k}, scope);
			}
			return result;
		}
	}

	/** Stat.get: a vanilla statistic's value. scope picks self or, in bi-entity code, other. */
	record StatCall(Scope scope, Node stat) implements Node {
		public Object eval(Context context) {
			return Integer.valueOf(context.stat(scope, stringArg(stat.eval(context), "Stat.get")));
		}
	}

	private static int slotArg(Object value, String where) {
		if (value instanceof Integer i) {
			return i;
		}
		throw new ScriptException("incompatible types: " + Ops.typeName(value) + " cannot be converted to int in " + where);
	}

	private static String stringArg(Object value, String where) {
		if (value instanceof String s) {
			return s;
		}
		throw new ScriptException("incompatible types: " + Ops.typeName(value) + " cannot be converted to String in " + where);
	}

	private static Object[] evalAll(List<Node> nodes, Context context) {
		Object[] values = new Object[nodes.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = nodes.get(i).eval(context);
		}
		return values;
	}
}
