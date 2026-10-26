package net.users.variables;

/**
 * Java operator semantics on boxed values: binary numeric promotion (JLS 5.6), int/long overflow
 * wrap-around, integer division by zero throwing, shifts masking their distance, String
 * concatenation, and Java's assignment conversions.
 *
 * Every result is boxed explicitly, because a ?: or switch with Integer and Long arms promotes both to Long.
 */
final class Ops {
	private Ops() {
	}

	static VarType promote(VarType a, VarType b) {
		return a.ordinal() >= b.ordinal() ? a : b;
	}

	static Object binary(String op, Object left, Object right) {
		switch (op) {
			case "+", "-", "*", "/", "%":
				return arithmetic(op, left, right);
			case "<<", ">>", ">>>":
				return shift(op, left, right);
			case "&", "|", "^":
				return bitwise(op, left, right);
			case "==":
				return Boolean.valueOf(equal(left, right));
			case "!=":
				return Boolean.valueOf(!equal(left, right));
			case "min", "max":
				// Only produced by modify_variable's min/max operations; code uses Math.min/Math.max.
				return Library.minMax(left, right, op.equals("max"), "Math." + op);
			default:
				return Boolean.valueOf(compare(op, left, right));
		}
	}

	static Object arithmetic(String op, Object left, Object right) {
		if (op.equals("+") && (left instanceof String || right instanceof String)) {
			return String.valueOf(left) + right;
		}
		if (!(left instanceof Number a) || !(right instanceof Number b)) {
			throw badOperands(op, left, right);
		}
		switch (promote(VarType.of(left), VarType.of(right))) {
			case INT: {
				int x = a.intValue();
				int y = b.intValue();
				return Integer.valueOf(switch (op) {
					case "+" -> x + y;
					case "-" -> x - y;
					case "*" -> x * y;
					case "/" -> x / y;
					default -> x % y;
				});
			}
			case LONG: {
				long x = a.longValue();
				long y = b.longValue();
				return Long.valueOf(switch (op) {
					case "+" -> x + y;
					case "-" -> x - y;
					case "*" -> x * y;
					case "/" -> x / y;
					default -> x % y;
				});
			}
			case FLOAT: {
				float x = a.floatValue();
				float y = b.floatValue();
				return Float.valueOf(switch (op) {
					case "+" -> x + y;
					case "-" -> x - y;
					case "*" -> x * y;
					case "/" -> x / y;
					default -> x % y;
				});
			}
			default: {
				double x = a.doubleValue();
				double y = b.doubleValue();
				return Double.valueOf(switch (op) {
					case "+" -> x + y;
					case "-" -> x - y;
					case "*" -> x * y;
					case "/" -> x / y;
					default -> x % y;
				});
			}
		}
	}

	/** The result has the (unary-promoted) type of the left operand, as in Java. */
	static Object shift(String op, Object left, Object right) {
		if (!isIntegral(left) || !isIntegral(right)) {
			throw badOperands(op, left, right);
		}
		long distance = ((Number) right).longValue();
		if (left instanceof Long l) {
			return Long.valueOf(switch (op) {
				case "<<" -> l << distance;
				case ">>" -> l >> distance;
				default -> l >>> distance;
			});
		}
		int x = (Integer) left;
		return Integer.valueOf(switch (op) {
			case "<<" -> x << distance;
			case ">>" -> x >> distance;
			default -> x >>> distance;
		});
	}

	/** &, | and ^ are logical (non-short-circuit) on booleans and bitwise on int/long. */
	static Object bitwise(String op, Object left, Object right) {
		if (left instanceof Boolean a && right instanceof Boolean b) {
			return Boolean.valueOf(switch (op) {
				case "&" -> a & b;
				case "|" -> a | b;
				default -> a ^ b;
			});
		}
		if (!isIntegral(left) || !isIntegral(right)) {
			throw badOperands(op, left, right);
		}
		if (left instanceof Long || right instanceof Long) {
			long x = ((Number) left).longValue();
			long y = ((Number) right).longValue();
			return Long.valueOf(switch (op) {
				case "&" -> x & y;
				case "|" -> x | y;
				default -> x ^ y;
			});
		}
		int x = (Integer) left;
		int y = (Integer) right;
		return Integer.valueOf(switch (op) {
			case "&" -> x & y;
			case "|" -> x | y;
			default -> x ^ y;
		});
	}

	/** Numbers compare by value after promotion. Deviation from Java: Strings also compare by value. */
	static boolean equal(Object left, Object right) {
		if (left instanceof Number a && right instanceof Number b) {
			VarType type = promote(VarType.of(left), VarType.of(right));
			if (type == VarType.INT || type == VarType.LONG) {
				return a.longValue() == b.longValue();
			}
			if (type == VarType.FLOAT) {
				return a.floatValue() == b.floatValue();
			}
			return a.doubleValue() == b.doubleValue();
		}
		if (VarType.of(left) == VarType.of(right)) {
			return left.equals(right);
		}
		throw new ScriptException("incomparable types: " + typeName(left) + " and " + typeName(right));
	}

	static boolean compare(String op, Object left, Object right) {
		if (!(left instanceof Number a) || !(right instanceof Number b)) {
			throw badOperands(op, left, right);
		}
		VarType type = promote(VarType.of(left), VarType.of(right));
		if (type == VarType.INT || type == VarType.LONG) {
			long x = a.longValue();
			long y = b.longValue();
			return switch (op) {
				case "<" -> x < y;
				case "<=" -> x <= y;
				case ">" -> x > y;
				default -> x >= y;
			};
		}
		if (type == VarType.FLOAT) {
			float x = a.floatValue();
			float y = b.floatValue();
			return switch (op) {
				case "<" -> x < y;
				case "<=" -> x <= y;
				case ">" -> x > y;
				default -> x >= y;
			};
		}
		double x = a.doubleValue();
		double y = b.doubleValue();
		return switch (op) {
			case "<" -> x < y;
			case "<=" -> x <= y;
			case ">" -> x > y;
			default -> x >= y;
		};
	}

	static Object unary(String op, Object value) {
		switch (op) {
			case "!":
				if (value instanceof Boolean b) {
					return Boolean.valueOf(!b);
				}
				break;
			case "~":
				if (value instanceof Integer i) {
					return Integer.valueOf(~i);
				}
				if (value instanceof Long l) {
					return Long.valueOf(~l);
				}
				break;
			case "-":
				if (value instanceof Integer i) {
					return Integer.valueOf(-i);
				}
				if (value instanceof Long l) {
					return Long.valueOf(-l);
				}
				if (value instanceof Float f) {
					return Float.valueOf(-f);
				}
				if (value instanceof Double d) {
					return Double.valueOf(-d);
				}
				break;
			default:
				if (value instanceof Number) {
					return value;
				}
				break;
		}
		throw new ScriptException("bad operand type " + typeName(value) + " for unary operator '" + op + "'");
	}

	/** (type) value. Numbers convert between each other like Java casts; boolean and String only cast to themselves. */
	static Object cast(Object value, VarType to) {
		if (to.isNumeric() && value instanceof Number) {
			return convert(value, to);
		}
		if (VarType.of(value) == to) {
			return value;
		}
		throw new ScriptException("incompatible types: " + typeName(value) + " cannot be converted to " + to.keyword());
	}

	/** A numeric cast. Number#xValue() matches Java's primitive casts, including saturation for float/double to int/long. */
	static Object convert(Object value, VarType to) {
		Number n = (Number) value;
		switch (to) {
			case INT:
				return Integer.valueOf(n.intValue());
			case LONG:
				return Long.valueOf(n.longValue());
			case FLOAT:
				return Float.valueOf(n.floatValue());
			case DOUBLE:
				return Double.valueOf(n.doubleValue());
			default:
				throw new IllegalStateException("not numeric: " + to);
		}
	}

	/** Plain '=': widening only (int -> long -> float -> double), as in Java. Narrowing needs an explicit cast. */
	static Object assign(Object value, VarType to, String name) {
		VarType from = VarType.of(value);
		if (from == to) {
			return value;
		}
		if (from.isNumeric() && to.isNumeric()) {
			if (from.ordinal() < to.ordinal()) {
				return convert(value, to);
			}
			throw new ScriptException("incompatible types: possible lossy conversion from " + from.keyword() + " to " + to.keyword()
				+ " when assigning " + name + "; add a cast like (" + to.keyword() + ")");
		}
		throw new ScriptException("incompatible types: " + from.keyword() + " cannot be converted to " + to.keyword() + " when assigning " + name);
	}

	/** x op= y means x = (T) (x op y): compound assignment casts implicitly, as in Java. */
	static Object compound(String op, Object current, Object operand, VarType to, String name) {
		Object result = binary(op, current, operand);
		if (to.isNumeric() && result instanceof Number) {
			return convert(result, to);
		}
		if (VarType.of(result) == to) {
			return result;
		}
		throw new ScriptException("incompatible types: " + typeName(result) + " cannot be converted to " + to.keyword() + " when assigning " + name);
	}

	static boolean bool(Object value, String where) {
		if (value instanceof Boolean b) {
			return b;
		}
		throw new ScriptException("incompatible types: " + typeName(value) + " cannot be converted to boolean in " + where);
	}

	static String typeName(Object value) {
		return VarType.of(value).keyword();
	}

	private static boolean isIntegral(Object value) {
		return value instanceof Integer || value instanceof Long;
	}

	private static ScriptException badOperands(String op, Object left, Object right) {
		return new ScriptException("bad operand types for binary operator '" + op + "': " + typeName(left) + " and " + typeName(right));
	}
}
