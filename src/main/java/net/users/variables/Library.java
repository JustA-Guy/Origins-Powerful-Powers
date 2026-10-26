package net.users.variables;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * The slice of the Java standard library code can call: Math, the wrapper classes' constants and
 * parse methods, String.valueOf, and instance methods on Strings. Overloads are resolved the way
 * javac would pick them for the argument types (e.g. Math.abs(int) stays int, Math.round(double)
 * returns long).
 */
final class Library {
	@FunctionalInterface
	private interface StaticFn {
		Object apply(Object[] args);
	}

	@FunctionalInterface
	private interface StringFn {
		Object apply(String receiver, Object[] args);
	}

	@FunctionalInterface
	private interface ListFn {
		Object apply(ListData list, Object[] args);
	}

	private static final Map<String, ListFn> LIST_METHODS = new HashMap<>();
	private static final Set<String> LIST_MUTATORS = Set.of("set", "add", "remove", "clear");

	private static final Set<String> CLASSES = Set.of("Math", "Integer", "Long", "Float", "Double", "Boolean", "String");
	private static final Map<String, Object> FIELDS = new HashMap<>();
	private static final Map<String, StaticFn> STATICS = new HashMap<>();
	private static final Map<String, StringFn> STRING_METHODS = new HashMap<>();

	static {
		FIELDS.put("Math.PI", Math.PI);
		FIELDS.put("Math.E", Math.E);
		FIELDS.put("Integer.MAX_VALUE", Integer.MAX_VALUE);
		FIELDS.put("Integer.MIN_VALUE", Integer.MIN_VALUE);
		FIELDS.put("Long.MAX_VALUE", Long.MAX_VALUE);
		FIELDS.put("Long.MIN_VALUE", Long.MIN_VALUE);
		FIELDS.put("Float.MAX_VALUE", Float.MAX_VALUE);
		FIELDS.put("Float.MIN_VALUE", Float.MIN_VALUE);
		FIELDS.put("Double.MAX_VALUE", Double.MAX_VALUE);
		FIELDS.put("Double.MIN_VALUE", Double.MIN_VALUE);
		FIELDS.put("Double.POSITIVE_INFINITY", Double.POSITIVE_INFINITY);
		FIELDS.put("Double.NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY);
		FIELDS.put("Double.NaN", Double.NaN);

		staticFn("Math.abs", 1, a -> {
			Number n = num(a[0], "Math.abs");
			switch (VarType.of(n)) {
				case INT:
					return Integer.valueOf(Math.abs(n.intValue()));
				case LONG:
					return Long.valueOf(Math.abs(n.longValue()));
				case FLOAT:
					return Float.valueOf(Math.abs(n.floatValue()));
				default:
					return Double.valueOf(Math.abs(n.doubleValue()));
			}
		});
		staticFn("Math.max", 2, a -> minMax(a[0], a[1], true, "Math.max"));
		staticFn("Math.min", 2, a -> minMax(a[0], a[1], false, "Math.min"));
		staticFn("Math.clamp", 3, a -> {
			Number value = num(a[0], "Math.clamp");
			Number min = num(a[1], "Math.clamp");
			Number max = num(a[2], "Math.clamp");
			if (min.doubleValue() > max.doubleValue()) {
				throw new ScriptException("Math.clamp: " + min + " > " + max);
			}
			return minMax(min, minMax(value, max, false, "Math.clamp"), true, "Math.clamp");
		});
		staticFn("Math.round", 1, a -> {
			Number n = num(a[0], "Math.round");
			if (n instanceof Double) {
				return Long.valueOf(Math.round(n.doubleValue()));
			}
			return Integer.valueOf(Math.round(n.floatValue()));
		});
		staticFn("Math.signum", 1, a -> {
			Number n = num(a[0], "Math.signum");
			if (n instanceof Double) {
				return Double.valueOf(Math.signum(n.doubleValue()));
			}
			return Float.valueOf(Math.signum(n.floatValue()));
		});
		staticFn("Math.floorDiv", 2, a -> {
			if (a[0] instanceof Integer x && a[1] instanceof Integer y) {
				return Integer.valueOf(Math.floorDiv(x, y));
			}
			return Long.valueOf(Math.floorDiv(longArg(a[0], "Math.floorDiv"), longArg(a[1], "Math.floorDiv")));
		});
		staticFn("Math.floorMod", 2, a -> {
			if (a[0] instanceof Integer x && a[1] instanceof Integer y) {
				return Integer.valueOf(Math.floorMod(x, y));
			}
			return Long.valueOf(Math.floorMod(longArg(a[0], "Math.floorMod"), longArg(a[1], "Math.floorMod")));
		});
		staticFn("Math.random", 0, a -> Double.valueOf(Math.random()));
		doubleFn("Math.sqrt", Math::sqrt);
		doubleFn("Math.cbrt", Math::cbrt);
		doubleFn("Math.floor", Math::floor);
		doubleFn("Math.ceil", Math::ceil);
		doubleFn("Math.rint", Math::rint);
		doubleFn("Math.sin", Math::sin);
		doubleFn("Math.cos", Math::cos);
		doubleFn("Math.tan", Math::tan);
		doubleFn("Math.asin", Math::asin);
		doubleFn("Math.acos", Math::acos);
		doubleFn("Math.atan", Math::atan);
		doubleFn("Math.toRadians", Math::toRadians);
		doubleFn("Math.toDegrees", Math::toDegrees);
		doubleFn("Math.exp", Math::exp);
		doubleFn("Math.log", Math::log);
		doubleFn("Math.log10", Math::log10);
		doubleFn("Math.pow", Math::pow);
		doubleFn("Math.atan2", Math::atan2);
		doubleFn("Math.hypot", Math::hypot);

		staticFn("Integer.parseInt", 1, a -> Integer.valueOf(Integer.parseInt(str(a[0], "Integer.parseInt"))));
		staticFn("Long.parseLong", 1, a -> Long.valueOf(Long.parseLong(str(a[0], "Long.parseLong"))));
		staticFn("Float.parseFloat", 1, a -> Float.valueOf(Float.parseFloat(str(a[0], "Float.parseFloat"))));
		staticFn("Double.parseDouble", 1, a -> Double.valueOf(Double.parseDouble(str(a[0], "Double.parseDouble"))));
		staticFn("Double.isNaN", 1, a -> Boolean.valueOf(Double.isNaN(num(a[0], "Double.isNaN").doubleValue())));
		staticFn("Double.isInfinite", 1, a -> Boolean.valueOf(Double.isInfinite(num(a[0], "Double.isInfinite").doubleValue())));
		staticFn("Boolean.parseBoolean", 1, a -> Boolean.valueOf(Boolean.parseBoolean(str(a[0], "Boolean.parseBoolean"))));
		staticFn("String.valueOf", 1, a -> String.valueOf(a[0]));

		stringFn("length", 0, (s, a) -> Integer.valueOf(s.length()));
		stringFn("isEmpty", 0, (s, a) -> Boolean.valueOf(s.isEmpty()));
		stringFn("isBlank", 0, (s, a) -> Boolean.valueOf(s.isBlank()));
		stringFn("equals", 1, (s, a) -> Boolean.valueOf(s.equals(a[0])));
		stringFn("equalsIgnoreCase", 1, (s, a) -> Boolean.valueOf(s.equalsIgnoreCase(str(a[0], "equalsIgnoreCase"))));
		stringFn("contains", 1, (s, a) -> Boolean.valueOf(s.contains(str(a[0], "contains"))));
		stringFn("startsWith", 1, (s, a) -> Boolean.valueOf(s.startsWith(str(a[0], "startsWith"))));
		stringFn("endsWith", 1, (s, a) -> Boolean.valueOf(s.endsWith(str(a[0], "endsWith"))));
		stringFn("indexOf", 1, (s, a) -> Integer.valueOf(s.indexOf(str(a[0], "indexOf"))));
		stringFn("lastIndexOf", 1, (s, a) -> Integer.valueOf(s.lastIndexOf(str(a[0], "lastIndexOf"))));
		stringFn("compareTo", 1, (s, a) -> Integer.valueOf(s.compareTo(str(a[0], "compareTo"))));
		stringFn("concat", 1, (s, a) -> s.concat(str(a[0], "concat")));
		stringFn("toUpperCase", 0, (s, a) -> s.toUpperCase(Locale.ROOT));
		stringFn("toLowerCase", 0, (s, a) -> s.toLowerCase(Locale.ROOT));
		stringFn("trim", 0, (s, a) -> s.trim());
		stringFn("strip", 0, (s, a) -> s.strip());
		stringFn("substring", 1, (s, a) -> s.substring(intArg(a[0], "substring")));
		stringFn("substring", 2, (s, a) -> s.substring(intArg(a[0], "substring"), intArg(a[1], "substring")));
		stringFn("replace", 2, (s, a) -> s.replace(str(a[0], "replace"), str(a[1], "replace")));
		stringFn("repeat", 1, (s, a) -> s.repeat(intArg(a[0], "repeat")));

		// java.util.List, for origins-powerful-powers:list. remove(int) removes by index, remove(anything else) by value.
		listFn("size", 0, (l, a) -> Integer.valueOf(l.size()));
		listFn("isEmpty", 0, (l, a) -> Boolean.valueOf(l.size() == 0));
		listFn("get", 1, (l, a) -> l.get(intArg(a[0], "get")));
		listFn("contains", 1, (l, a) -> Boolean.valueOf(l.contains(a[0])));
		listFn("indexOf", 1, (l, a) -> Integer.valueOf(l.indexOf(a[0])));
		listFn("set", 2, (l, a) -> l.set(intArg(a[0], "set"), a[1]));
		listFn("add", 1, (l, a) -> {
			l.add(a[0]);
			return Boolean.TRUE;
		});
		listFn("add", 2, (l, a) -> {
			l.add(intArg(a[0], "add"), a[1]);
			return null;
		});
		listFn("remove", 1, (l, a) -> {
			if (a[0] instanceof Integer index) {
				return l.removeAt(index);
			}
			return Boolean.valueOf(l.removeValue(a[0]));
		});
		listFn("clear", 0, (l, a) -> {
			l.clear();
			return null;
		});
	}

	private Library() {
	}

	static boolean isClass(String name) {
		return CLASSES.contains(name);
	}

	static boolean hasField(String owner, String name) {
		return FIELDS.containsKey(owner + "." + name);
	}

	static Object field(String owner, String name) {
		return FIELDS.get(owner + "." + name);
	}

	static boolean hasStatic(String owner, String name, int arity) {
		return STATICS.containsKey(owner + "." + name + "/" + arity);
	}

	static Object callStatic(String owner, String name, Object[] args) {
		return STATICS.get(owner + "." + name + "/" + args.length).apply(args);
	}

	static boolean hasStringMethod(String name, int arity) {
		return STRING_METHODS.containsKey(name + "/" + arity);
	}

	static Object callString(Object receiver, String name, Object[] args) {
		if (!(receiver instanceof String s)) {
			throw new ScriptException(Ops.typeName(receiver) + " cannot be dereferenced (only Strings and lists have methods), calling " + name + "()");
		}
		StringFn fn = STRING_METHODS.get(name + "/" + args.length);
		if (fn == null) {
			throw new ScriptException("String has no method " + name + " taking " + args.length + " argument(s)");
		}
		return fn.apply(s, args);
	}

	static boolean hasListMethod(String name, int arity) {
		return LIST_METHODS.containsKey(name + "/" + arity);
	}

	/** Methods that change a list: not allowed in conditions or modify_variable values. */
	static boolean isListMutator(String name) {
		return LIST_MUTATORS.contains(name);
	}

	static Object callList(ListData list, String name, Object[] args) {
		ListFn fn = LIST_METHODS.get(name + "/" + args.length);
		if (fn == null) {
			throw new ScriptException(list.name() + " is a list, which has no method " + name + " taking " + args.length
				+ " argument(s); lists have size(), isEmpty(), get(i), set(i, v), add(v), add(i, v), remove(i), clear(), contains(v), indexOf(v)");
		}
		return fn.apply(list, args);
	}

	private static void listFn(String name, int arity, ListFn fn) {
		LIST_METHODS.put(name + "/" + arity, fn);
	}

	private static void staticFn(String name, int arity, StaticFn fn) {
		STATICS.put(name + "/" + arity, fn);
	}

	private static void doubleFn(String name, DoubleUnaryOperator fn) {
		staticFn(name, 1, a -> Double.valueOf(fn.applyAsDouble(num(a[0], name).doubleValue())));
	}

	private static void doubleFn(String name, DoubleBinaryOperator fn) {
		staticFn(name, 2, a -> Double.valueOf(fn.applyAsDouble(num(a[0], name).doubleValue(), num(a[1], name).doubleValue())));
	}

	private static void stringFn(String name, int arity, StringFn fn) {
		STRING_METHODS.put(name + "/" + arity, fn);
	}

	static Object minMax(Object first, Object second, boolean max, String where) {
		Number x = num(first, where);
		Number y = num(second, where);
		switch (Ops.promote(VarType.of(x), VarType.of(y))) {
			case INT:
				return Integer.valueOf(max ? Math.max(x.intValue(), y.intValue()) : Math.min(x.intValue(), y.intValue()));
			case LONG:
				return Long.valueOf(max ? Math.max(x.longValue(), y.longValue()) : Math.min(x.longValue(), y.longValue()));
			case FLOAT:
				return Float.valueOf(max ? Math.max(x.floatValue(), y.floatValue()) : Math.min(x.floatValue(), y.floatValue()));
			default:
				return Double.valueOf(max ? Math.max(x.doubleValue(), y.doubleValue()) : Math.min(x.doubleValue(), y.doubleValue()));
		}
	}

	private static Number num(Object value, String where) {
		if (value instanceof Number n) {
			return n;
		}
		throw new ScriptException("incompatible types: " + Ops.typeName(value) + " cannot be converted to a number in " + where);
	}

	private static String str(Object value, String where) {
		if (value instanceof String s) {
			return s;
		}
		throw new ScriptException("incompatible types: " + Ops.typeName(value) + " cannot be converted to String in " + where);
	}

	private static int intArg(Object value, String where) {
		if (value instanceof Integer i) {
			return i;
		}
		if (value instanceof Number) {
			throw new ScriptException("incompatible types: possible lossy conversion from " + Ops.typeName(value) + " to int in " + where);
		}
		throw new ScriptException("incompatible types: " + Ops.typeName(value) + " cannot be converted to int in " + where);
	}

	private static long longArg(Object value, String where) {
		if (value instanceof Integer || value instanceof Long) {
			return ((Number) value).longValue();
		}
		if (value instanceof Number) {
			throw new ScriptException("incompatible types: possible lossy conversion from " + Ops.typeName(value) + " to long in " + where);
		}
		throw new ScriptException("incompatible types: " + Ops.typeName(value) + " cannot be converted to long in " + where);
	}
}
