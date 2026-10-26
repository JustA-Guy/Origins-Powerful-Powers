package net.users.variables;

import net.users.variables.Lexer.Kind;
import net.users.variables.Lexer.Token;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive-descent parser for the Java expression subset. Operator precedence and associativity
 * follow JLS 15. Action code is expression statements separated by ';' (no control flow); a
 * condition is one expression; a field list is Java field declarations.
 */
final class Parser {
	/** FOR_EACH is ACTION plus the right to assign value (the current element) inside a for_each code block. */
	enum Mode {
		ACTION, CONDITION, FIELDS, VALUE, FOR_EACH
	}

	/** Names of the innermost running loop; reserved everywhere. */
	private static final Set<String> LOOP_NAMES = Set.of("value", "index", "size");

	private static final Set<String> ASSIGN_OPS = Set.of("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=");
	// Lowest precedence first: || down to * / %.
	private static final List<Set<String>> BINARY_LEVELS = List.of(
		Set.of("||"), Set.of("&&"), Set.of("|"), Set.of("^"), Set.of("&"),
		Set.of("==", "!="), Set.of("<", "<=", ">", ">="), Set.of("<<", ">>", ">>>"),
		Set.of("+", "-"), Set.of("*", "/", "%")
	);
	private static final Set<String> RESERVED = Set.of(
		"self", "other", "true", "false", "null", "new", "this", "if", "else", "for", "while", "do",
		"return", "class", "void", "char", "byte", "short", "final", "static", "Scoreboard", "Item", "Death", "Stat", "Global"
	);
	private static final Set<String> GLOBAL_WRITES = Set.of("set", "delete", "bind", "lock", "unlock");
	private static final Set<String> DEATH_METHODS = Set.of("has", "x", "y", "z", "dimension", "distance");

	private final String source;
	private final List<Token> tokens;
	private final Mode mode;
	private final boolean allowOther;
	private int index;

	Parser(String source, Mode mode, boolean allowOther) {
		this.source = source;
		this.tokens = Lexer.tokenize(source);
		this.mode = mode;
		this.allowOther = allowOther;
	}

	List<Node> parseStatements() {
		List<Node> body = new ArrayList<>();
		while (!atEof()) {
			if (acceptOp(";")) {
				continue;
			}
			Token start = current();
			Node statement = parseExpression();
			if (!(statement instanceof Node.Assign || statement instanceof Node.IncDec
				|| statement instanceof Node.StaticCall || statement instanceof Node.MethodCall
				|| statement instanceof Node.GlobalCall call && (GLOBAL_WRITES.contains(call.method()) || call.method().equals("list")))) {
				throw error(start, "not a statement (only assignments, ++/-- and method calls can stand alone; did you mean += ?)");
			}
			body.add(statement);
			if (!atEof()) {
				expectOp(";");
			}
		}
		if (body.isEmpty()) {
			throw error(current(), "the code is empty");
		}
		return body;
	}

	Node parseCondition() {
		Node expression = parseExpression();
		acceptOp(";");
		if (!atEof()) {
			throw error(current(), mode == Mode.CONDITION
				? "a condition is a single expression; combine checks with && or ||"
				: "expected a single expression");
		}
		return expression;
	}

	/** modify_variable's "variable": coins, self.coins, or (bientity only) other.coins. */
	static Node.Var variableReference(String source, boolean bientity) {
		Parser parser = new Parser(source, Mode.VALUE, bientity);
		Token start = parser.current();
		Node node = parser.parsePrimary();
		if (!(node instanceof Node.Var variable) || !parser.atEof()) {
			throw parser.error(start, "expected a variable name, like coins" + (bientity ? " or other.coins" : ""));
		}
		return variable;
	}

	/** A loop's "list" naming a global list: Global.list("key"), where the key can be any String expression. Null if source isn't one. */
	static Node globalListReference(String source, boolean bientity) {
		if (!source.trim().startsWith("Global.")) {
			return null;
		}
		Parser parser = new Parser(source, Mode.VALUE, bientity);
		Token start = parser.current();
		Node node = parser.parsePrimary();
		if (!(node instanceof Node.GlobalCall call) || !call.method().equals("list") || call.args().size() != 1 || !parser.atEof()) {
			throw parser.error(start, "expected a global list, like Global.list(\"mypack:members\")");
		}
		return node;
	}

	/** modify_variable's "value": one expression that doesn't assign anything. */
	static Node valueExpression(String source, boolean bientity) {
		return new Parser(source, Mode.VALUE, bientity).parseCondition();
	}

	/** "int mana = 100, maxMana = mana; boolean casting;" Initializers are evaluated now, at load. */
	FieldList parseFields() {
		Map<String, FieldDecl> fields = new LinkedHashMap<>();
		while (!atEof()) {
			if (acceptOp(";")) {
				continue;
			}
			Token typeToken = expectIdent("a type");
			VarType type = VarType.byKeyword(typeToken.text());
			if (type == null) {
				throw error(typeToken, "expected a type (int, long, float, double, boolean or String) but found '" + typeToken.text() + "'");
			}
			do {
				Token nameToken = expectIdent("a variable name");
				String name = checkName(nameToken);
				if (fields.containsKey(name)) {
					throw error(nameToken, "variable " + name + " is already defined");
				}
				Object value = type.defaultValue();
				if (acceptOp("=")) {
					Node initializer = parseExpression();
					try {
						value = Ops.assign(initializer.eval(new Context.Initializers(fields)), type, name);
					} catch (RuntimeException e) {
						throw error(nameToken, e.getMessage());
					}
				}
				fields.put(name, new FieldDecl(name, type, value));
			} while (acceptOp(","));
			if (!atEof()) {
				expectOp(";");
			}
		}
		if (fields.isEmpty()) {
			throw error(current(), "declare at least one variable, like: int mana = 0;");
		}
		return new FieldList(source, fields);
	}

	private Node parseExpression() {
		return parseAssignment();
	}

	private Node parseAssignment() {
		Node left = parseTernary();
		Token t = current();
		if (t.kind() == Kind.OP && ASSIGN_OPS.contains(t.text())) {
			if (!(left instanceof Node.Var target)) {
				throw error(t, "the left side of " + t.text() + " must be a variable");
			}
			if (!canAssign()) {
				throw error(t, noAssignMessage());
			}
			checkLoopTarget(t, target);
			index++;
			Node value = parseAssignment();
			String op = t.text().equals("=") ? null : t.text().substring(0, t.text().length() - 1);
			return new Node.Assign(target, op, value);
		}
		return left;
	}

	private Node parseTernary() {
		Node condition = parseBinary(0);
		if (acceptOp("?")) {
			Node ifTrue = parseExpression();
			expectOp(":");
			Node ifFalse = parseTernary();
			return new Node.Ternary(condition, ifTrue, ifFalse);
		}
		return condition;
	}

	private Node parseBinary(int level) {
		if (level == BINARY_LEVELS.size()) {
			return parseUnary();
		}
		Node left = parseBinary(level + 1);
		while (current().kind() == Kind.OP && BINARY_LEVELS.get(level).contains(current().text())) {
			String op = current().text();
			index++;
			Node right = parseBinary(level + 1);
			if (op.equals("&&") || op.equals("||")) {
				left = new Node.Logical(op, left, right);
			} else {
				left = new Node.Binary(op, left, right);
			}
		}
		return left;
	}

	private Node parseUnary() {
		Token t = current();
		if (t.kind() == Kind.OP) {
			switch (t.text()) {
				case "-":
					index++;
					// Folded into the literal so the minimum int and minimum long are in range.
					if (current().kind() == Kind.NUMBER) {
						Token number = current();
						index++;
						return new Node.Literal(number(number, true));
					}
					return new Node.Unary("-", parseUnary());
				case "+", "!", "~":
					index++;
					return new Node.Unary(t.text(), parseUnary());
				case "++", "--":
					index++;
					return incDec(t, parseUnary(), true);
				case "(":
					if (isCast()) {
						VarType type = VarType.byKeyword(peek(1).text());
						index += 3;
						return new Node.Cast(type, parseUnary());
					}
					break;
				default:
					break;
			}
		}
		return parsePostfix();
	}

	private Node parsePostfix() {
		Node node = parsePrimary();
		if (node instanceof Node.GlobalCall global && global.method().equals("list") && global.args().size() == 1 && !atOp(".")) {
			throw error(current(), "Global.list(key) is a list, not a value; call a list method on it, like Global.list(key).size(), or create it with Global.list(key, type)");
		}
		while (atOp(".")) {
			index++;
			Token method = expectIdent("a method name");
			if (!atOp("(")) {
				throw error(method, "values have no fields; methods need parentheses, like " + method.text() + "()");
			}
			List<Node> args = parseArguments();
			boolean listMethod = Library.hasListMethod(method.text(), args.size());
			if (!listMethod && !Library.hasStringMethod(method.text(), args.size())) {
				throw error(method, "cannot find method " + method.text() + " taking " + args.size() + " argument(s) (only String and list methods exist)");
			}
			if (listMethod && Library.isListMutator(method.text()) && !canAssign()) {
				throw error(method, mode == Mode.CONDITION ? "conditions can't change lists" : "can't change a list here");
			}
			node = new Node.MethodCall(node, method.text(), args);
		}
		if (atOp("++") || atOp("--")) {
			Token op = current();
			index++;
			return incDec(op, node, false);
		}
		return node;
	}

	private Node parsePrimary() {
		Token t = current();
		if (t.kind() == Kind.NUMBER) {
			index++;
			return new Node.Literal(number(t, false));
		}
		if (t.kind() == Kind.STRING) {
			index++;
			return new Node.Literal(t.value());
		}
		if (atOp("(")) {
			index++;
			Node inner = parseExpression();
			expectOp(")");
			return inner;
		}
		if (t.kind() != Kind.IDENT) {
			throw error(t, "expected a value but found " + describe(t));
		}
		index++;
		String name = t.text();
		switch (name) {
			case "true":
				return new Node.Literal(Boolean.TRUE);
			case "false":
				return new Node.Literal(Boolean.FALSE);
			case "null":
				throw error(t, "null isn't supported");
			case "self", "other": {
				if (name.equals("other") && !allowOther) {
					throw error(t, "'other' only exists in bientity conditions and actions");
				}
				expectOp(".");
				Token field = expectIdent("a variable name");
				return new Node.Var(name.equals("self") ? Node.Scope.SELF : Node.Scope.OTHER, checkName(field));
			}
			default:
				break;
		}
		if (LOOP_NAMES.contains(name)) {
			return new Node.Var(Node.Scope.LOOP, name);
		}
		if (name.equals("Scoreboard")) {
			return scoreboardCall();
		}
		if (name.equals("Item")) {
			return itemCall();
		}
		if (name.equals("Death")) {
			return deathCall();
		}
		if (name.equals("Stat")) {
			return statCall();
		}
		if (name.equals("Global")) {
			return globalCall();
		}
		if (Library.isClass(name)) {
			expectOp(".");
			Token member = expectIdent("a member of " + name);
			if (atOp("(")) {
				List<Node> args = parseArguments();
				if (!Library.hasStatic(name, member.text(), args.size())) {
					throw error(member, "cannot find method " + name + "." + member.text() + " taking " + args.size() + " argument(s)");
				}
				return new Node.StaticCall(name, member.text(), args);
			}
			if (!Library.hasField(name, member.text())) {
				throw error(member, "cannot find " + name + "." + member.text());
			}
			return new Node.Literal(Library.field(name, member.text()));
		}
		if (VarType.byKeyword(name) != null) {
			throw error(t, "'" + name + "' is a type; declare variables in a origins-powerful-powers:variables power");
		}
		if (atOp("(")) {
			throw error(t, "cannot find method " + name + "(); built-in methods are called on their class, like Math." + name + "(...)");
		}
		if (RESERVED.contains(name)) {
			throw error(t, "'" + name + "' isn't supported");
		}
		return new Node.Var(Node.Scope.SELF, name);
	}

	/** Scoreboard.get(objective), Scoreboard.get(holder, objective), and the same two for has. Read-only, so allowed everywhere. */
	private Node scoreboardCall() {
		expectOp(".");
		Token member = expectIdent("a member of Scoreboard");
		boolean known = member.text().equals("get") || member.text().equals("has");
		List<Node> args = atOp("(") ? parseArguments() : null;
		if (!known || args == null || args.isEmpty() || args.size() > 2) {
			throw error(member, "cannot find Scoreboard." + member.text() + (args == null ? "" : " taking " + args.size() + " argument(s)")
				+ "; Scoreboard has get(objective), get(holder, objective), has(objective) and has(holder, objective)");
		}
		return new Node.ScoreCall(member.text().equals("has"), args);
	}

	/**
	 * Item.id and Item.count: Item.id(slot) for a vanilla slot, Item.id(power, slot) for an
	 * origins:inventory power. Item.cooldown(item or slot) for vanilla item cooldowns. Each optionally
	 * starts with self or other. Read-only, so allowed everywhere.
	 */
	private Node itemCall() {
		expectOp(".");
		Token member = expectIdent("a member of Item");
		boolean cooldown = member.text().equals("cooldown");
		boolean known = member.text().equals("id") || member.text().equals("count") || cooldown;
		Node.Scope scope = Node.Scope.SELF;
		List<Node> args = null;
		if (known && atOp("(")) {
			expectOp("(");
			Token first = current();
			// "self" and "other" name whose inventory to read; everywhere else they need a variable after them.
			if (first.kind() == Kind.IDENT && (first.text().equals("self") || first.text().equals("other")) && peek(1).kind() == Kind.OP
				&& peek(1).text().equals(",")) {
				if (first.text().equals("other") && !allowOther) {
					throw error(first, "'other' only exists in bientity conditions and actions");
				}
				scope = first.text().equals("other") ? Node.Scope.OTHER : Node.Scope.SELF;
				index += 2;
			}
			args = new ArrayList<>();
			if (!acceptOp(")")) {
				do {
					args.add(parseExpression());
				} while (acceptOp(","));
				expectOp(")");
			}
		}
		if (!known || args == null || args.isEmpty() || args.size() > (cooldown ? 1 : 2)) {
			throw error(member, "cannot find Item." + member.text() + (args == null ? "" : " taking " + args.size() + " argument(s)")
				+ "; Item has id(slot), id(power, slot), count(slot), count(power, slot) and cooldown(item or slot), each optionally starting with self or other");
		}
		return new Node.ItemCall(scope, member.text(), args);
	}

	/** Death.has(), Death.x() / y() / z(), Death.dimension() and Death.distance(), each optionally Death.x(other). Read-only. */
	private Node deathCall() {
		expectOp(".");
		Token member = expectIdent("a member of Death");
		Node.Scope scope = Node.Scope.SELF;
		boolean called = false;
		if (DEATH_METHODS.contains(member.text()) && atOp("(")) {
			expectOp("(");
			Token first = current();
			if (first.kind() == Kind.IDENT && (first.text().equals("self") || first.text().equals("other"))) {
				if (first.text().equals("other") && !allowOther) {
					throw error(first, "'other' only exists in bientity conditions and actions");
				}
				scope = first.text().equals("other") ? Node.Scope.OTHER : Node.Scope.SELF;
				index++;
			}
			called = acceptOp(")");
		}
		if (!called) {
			throw error(member, "cannot find Death." + member.text()
				+ "; Death has has(), x(), y(), z(), dimension() and distance(), each optionally taking self or other");
		}
		return new Node.DeathCall(scope, member.text());
	}

	/**
	 * Global.has(key), get(key), set(key, value), delete(key), bind(key, self|other), lock(key, owner), unlock(key),
	 * list(key) and list(key, type). Everything that changes the store is only allowed where code can assign.
	 */
	private Node globalCall() {
		expectOp(".");
		Token member = expectIdent("a member of Global");
		String method = member.text();
		int min;
		int max;
		switch (method) {
			case "has", "get", "delete", "unlock" -> { min = 1; max = 1; }
			case "set", "lock", "bind" -> { min = 2; max = 2; }
			case "list" -> { min = 1; max = 2; }
			default -> { min = -1; max = -1; }
		}
		if (min < 0 || !atOp("(")) {
			throw error(member, "cannot find Global." + method + "; Global has has(key), get(key), set(key, value), delete(key), bind(key, self or other), "
				+ "lock(key, owner), unlock(key), list(key) and list(key, type)");
		}
		if ((GLOBAL_WRITES.contains(method) || method.equals("list") && peekArgumentCount() == 2) && !canAssign()) {
			throw error(member, mode == Mode.CONDITION ? "conditions can't change global values" : "global values can't be changed here");
		}
		expectOp("(");
		List<Node> args = new ArrayList<>();
		Node.Scope bindScope = null;
		args.add(parseExpression());
		if (method.equals("bind")) {
			expectOp(",");
			Token who = expectIdent("self or other");
			if (!who.text().equals("self") && !who.text().equals("other")) {
				throw error(who, "Global.bind takes self or other as its second argument");
			}
			if (who.text().equals("other") && !allowOther) {
				throw error(who, "'other' only exists in bientity conditions and actions");
			}
			bindScope = who.text().equals("other") ? Node.Scope.OTHER : Node.Scope.SELF;
		} else {
			while (acceptOp(",")) {
				args.add(parseExpression());
			}
		}
		expectOp(")");
		if (args.size() + (bindScope == null ? 0 : 1) < min || args.size() + (bindScope == null ? 0 : 1) > max) {
			throw error(member, "cannot find Global." + method + " taking " + args.size() + " argument(s)");
		}
		return new Node.GlobalCall(method, args, bindScope);
	}

	/** How many top-level arguments the call starting at the current ( has, without consuming anything. */
	private int peekArgumentCount() {
		int depth = 0;
		int count = 1;
		for (int i = index; i < tokens.size(); i++) {
			Token t = tokens.get(i);
			if (t.kind() == Kind.EOF) {
				break;
			}
			if (t.kind() == Kind.OP && t.text().equals("(")) {
				depth++;
			} else if (t.kind() == Kind.OP && t.text().equals(")")) {
				if (--depth == 0) {
					return i == index + 1 ? 0 : count;
				}
			} else if (depth == 1 && t.kind() == Kind.OP && t.text().equals(",")) {
				count++;
			}
		}
		return count;
	}

	/** Stat.get(stat) and Stat.get(self|other, stat): a vanilla statistic, named like a scoreboard criterion. Read-only. */
	private Node statCall() {
		expectOp(".");
		Token member = expectIdent("a member of Stat");
		Node.Scope scope = Node.Scope.SELF;
		Node stat = null;
		if (member.text().equals("get") && atOp("(")) {
			expectOp("(");
			Token first = current();
			if (first.kind() == Kind.IDENT && (first.text().equals("self") || first.text().equals("other")) && peek(1).kind() == Kind.OP
				&& peek(1).text().equals(",")) {
				if (first.text().equals("other") && !allowOther) {
					throw error(first, "'other' only exists in bientity conditions and actions");
				}
				scope = first.text().equals("other") ? Node.Scope.OTHER : Node.Scope.SELF;
				index += 2;
			}
			if (!atOp(")")) {
				stat = parseExpression();
			}
			if (!acceptOp(")")) {
				stat = null;
			}
		}
		if (stat == null) {
			throw error(member, "cannot find Stat." + member.text()
				+ "; Stat has get(stat) and get(self or other, stat), with stat named like \"minecraft.custom:minecraft.jump\"");
		}
		return new Node.StatCall(scope, stat);
	}

	private List<Node> parseArguments() {
		expectOp("(");
		List<Node> args = new ArrayList<>();
		if (acceptOp(")")) {
			return args;
		}
		do {
			args.add(parseExpression());
		} while (acceptOp(","));
		expectOp(")");
		return args;
	}

	private Node incDec(Token op, Node operand, boolean prefix) {
		if (!(operand instanceof Node.Var target)) {
			throw error(op, op.text() + " needs a variable");
		}
		if (!canAssign()) {
			throw error(op, noAssignMessage());
		}
		checkLoopTarget(op, target);
		return new Node.IncDec(target, op.text().equals("++"), prefix);
	}

	private boolean canAssign() {
		return mode == Mode.ACTION || mode == Mode.FOR_EACH;
	}

	/** index and size are never writable; value only in a for_each code block. */
	private void checkLoopTarget(Token t, Node.Var target) {
		if (target.scope() != Node.Scope.LOOP) {
			return;
		}
		if (!target.name().equals("value")) {
			throw error(t, target.name() + " is read-only");
		}
		if (mode != Mode.FOR_EACH) {
			throw error(t, "value can only be changed inside a origins-powerful-powers:for_each code block; everywhere else, including inside for_each_read, it's read-only");
		}
	}

	private boolean isCast() {
		Token type = peek(1);
		Token close = peek(2);
		return type.kind() == Kind.IDENT && VarType.byKeyword(type.text()) != null
			&& close.kind() == Kind.OP && close.text().equals(")");
	}

	private Object number(Token t, boolean negate) {
		String text = t.text().replace("_", "");
		char suffix = Character.toLowerCase(text.charAt(text.length() - 1));
		try {
			if (text.length() > 1 && text.charAt(0) == '0' && (text.charAt(1) == 'x' || text.charAt(1) == 'X')) {
				boolean isLong = suffix == 'l';
				String digits = text.substring(2, text.length() - (isLong ? 1 : 0));
				if (digits.isEmpty()) {
					throw error(t, "hexadecimal numbers must contain at least one digit");
				}
				if (isLong) {
					long value = Long.parseUnsignedLong(digits, 16);
					return Long.valueOf(negate ? -value : value);
				}
				// Like Java, an int hex literal may use all 32 bits: 0xFFFFFFFF is -1.
				long value = Long.parseLong(digits, 16);
				if (value > 0xFFFFFFFFL) {
					throw error(t, "integer number too large");
				}
				int intValue = (int) value;
				return Integer.valueOf(negate ? -intValue : intValue);
			}
			boolean floating = text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0
				|| suffix == 'f' || suffix == 'd';
			if (floating) {
				String body = suffix == 'f' || suffix == 'd' ? text.substring(0, text.length() - 1) : text;
				if (suffix == 'f') {
					float value = Float.parseFloat(body);
					if (Float.isInfinite(value)) {
						throw error(t, "floating-point number too large");
					}
					return Float.valueOf(negate ? -value : value);
				}
				double value = Double.parseDouble(body);
				if (Double.isInfinite(value)) {
					throw error(t, "floating-point number too large");
				}
				return Double.valueOf(negate ? -value : value);
			}
			boolean isLong = suffix == 'l';
			String digits = isLong ? text.substring(0, text.length() - 1) : text;
			if (digits.length() > 1 && digits.charAt(0) == '0') {
				throw error(t, "octal literals aren't supported; remove the leading 0");
			}
			BigInteger value = new BigInteger(digits);
			if (negate) {
				value = value.negate();
			}
			if (isLong) {
				if (value.bitLength() > 63) {
					throw error(t, "long number too large");
				}
				return Long.valueOf(value.longValue());
			}
			if (value.bitLength() > 31) {
				throw error(t, "integer number too large; add an L suffix for a long");
			}
			return Integer.valueOf(value.intValue());
		} catch (NumberFormatException e) {
			throw error(t, "malformed number");
		}
	}

	private String checkName(Token t) {
		String name = t.text();
		if (RESERVED.contains(name) || LOOP_NAMES.contains(name) || VarType.byKeyword(name) != null || Library.isClass(name)) {
			throw error(t, "'" + name + "' is reserved and can't be a variable name");
		}
		return name;
	}

	private String noAssignMessage() {
		return switch (mode) {
			case CONDITION -> "conditions can't change variables; use a origins-powerful-powers:execute action";
			case VALUE -> "the value of modify_variable can't change variables";
			default -> "a field initializer can't assign variables";
		};
	}

	private Token current() {
		return tokens.get(index);
	}

	private Token peek(int offset) {
		return tokens.get(Math.min(index + offset, tokens.size() - 1));
	}

	private boolean atEof() {
		return current().kind() == Kind.EOF;
	}

	private boolean atOp(String op) {
		Token t = current();
		return t.kind() == Kind.OP && t.text().equals(op);
	}

	private boolean acceptOp(String op) {
		if (atOp(op)) {
			index++;
			return true;
		}
		return false;
	}

	private void expectOp(String op) {
		if (!acceptOp(op)) {
			throw error(current(), "expected '" + op + "' but found " + describe(current()));
		}
	}

	private Token expectIdent(String what) {
		Token t = current();
		if (t.kind() != Kind.IDENT) {
			throw error(t, "expected " + what + " but found " + describe(t));
		}
		index++;
		return t;
	}

	private static String describe(Token t) {
		return t.kind() == Kind.EOF ? "the end of the code" : "'" + t.text() + "'";
	}

	private ScriptException error(Token t, String message) {
		return ScriptException.at(source, t.pos(), message);
	}
}
