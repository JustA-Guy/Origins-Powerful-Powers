package net.users.variables;

import java.util.ArrayList;
import java.util.List;

/** Splits code into Java tokens: identifiers, number and string literals, operators. Skips // and block comments. */
final class Lexer {
	enum Kind {
		IDENT, NUMBER, STRING, OP, EOF
	}

	/** For STRING tokens, value is the unescaped text. NUMBER tokens are converted by the parser. */
	record Token(Kind kind, String text, Object value, int pos) {
	}

	// Longest first, so ">>>=" wins over ">>" and ">".
	private static final String[] OPERATORS = {
		">>>=", "<<=", ">>=", ">>>", "++", "--", "&&", "||", "==", "!=", "<=", ">=",
		"+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>",
		"+", "-", "*", "/", "%", "!", "~", "&", "|", "^", "<", ">", "=", "?", ":", "(", ")", ",", ";", "."
	};

	private Lexer() {
	}

	static List<Token> tokenize(String source) {
		List<Token> tokens = new ArrayList<>();
		int i = 0;
		int n = source.length();
		while (i < n) {
			char c = source.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}
			if (source.startsWith("//", i)) {
				while (i < n && source.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (source.startsWith("/*", i)) {
				int end = source.indexOf("*/", i + 2);
				if (end < 0) {
					throw ScriptException.at(source, i, "unclosed comment");
				}
				i = end + 2;
				continue;
			}
			int start = i;
			if (Character.isJavaIdentifierStart(c)) {
				while (i < n && Character.isJavaIdentifierPart(source.charAt(i))) {
					i++;
				}
				tokens.add(new Token(Kind.IDENT, source.substring(start, i), null, start));
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(source.charAt(i + 1)))) {
				i = scanNumber(source, i);
				if (i < n && Character.isJavaIdentifierPart(source.charAt(i))) {
					throw ScriptException.at(source, start, "malformed number");
				}
				tokens.add(new Token(Kind.NUMBER, source.substring(start, i), null, start));
			} else if (c == '"') {
				i = scanString(source, i, tokens);
			} else if (c == '\'') {
				throw ScriptException.at(source, i, "char literals aren't supported; use a String (\"...\")");
			} else {
				String op = null;
				for (String candidate : OPERATORS) {
					if (source.startsWith(candidate, i)) {
						op = candidate;
						break;
					}
				}
				if (op == null) {
					throw ScriptException.at(source, i, "illegal character '" + c + "'");
				}
				i += op.length();
				tokens.add(new Token(Kind.OP, op, null, start));
			}
		}
		tokens.add(new Token(Kind.EOF, "", null, n));
		return tokens;
	}

	private static int scanNumber(String source, int i) {
		int n = source.length();
		if (source.charAt(i) == '0' && i + 1 < n && (source.charAt(i + 1) == 'x' || source.charAt(i + 1) == 'X')) {
			i += 2;
			while (i < n && (Character.digit(source.charAt(i), 16) >= 0 || source.charAt(i) == '_')) {
				i++;
			}
			if (i < n && (source.charAt(i) == 'L' || source.charAt(i) == 'l')) {
				i++;
			}
			return i;
		}
		i = skipDigits(source, i);
		// "1." is a double, but "1.foo" is not a number followed by a member access.
		if (i < n && source.charAt(i) == '.' && (i + 1 >= n || !Character.isJavaIdentifierStart(source.charAt(i + 1)))) {
			i = skipDigits(source, i + 1);
		}
		if (i < n && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
			int j = i + 1;
			if (j < n && (source.charAt(j) == '+' || source.charAt(j) == '-')) {
				j++;
			}
			if (j < n && Character.isDigit(source.charAt(j))) {
				i = skipDigits(source, j);
			}
		}
		if (i < n && "lLfFdD".indexOf(source.charAt(i)) >= 0) {
			i++;
		}
		return i;
	}

	private static int skipDigits(String source, int i) {
		while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) {
			i++;
		}
		return i;
	}

	private static int scanString(String source, int start, List<Token> tokens) {
		StringBuilder text = new StringBuilder();
		int i = start + 1;
		int n = source.length();
		while (true) {
			if (i >= n || source.charAt(i) == '\n') {
				throw ScriptException.at(source, start, "unclosed string literal");
			}
			char c = source.charAt(i++);
			if (c == '"') {
				break;
			}
			if (c != '\\') {
				text.append(c);
				continue;
			}
			if (i >= n) {
				throw ScriptException.at(source, start, "unclosed string literal");
			}
			char escape = source.charAt(i++);
			switch (escape) {
				case 'n' -> text.append('\n');
				case 't' -> text.append('\t');
				case 'r' -> text.append('\r');
				case 'b' -> text.append('\b');
				case 'f' -> text.append('\f');
				case 's' -> text.append(' ');
				case '0' -> text.append('\0');
				case '"', '\'', '\\' -> text.append(escape);
				case 'u' -> {
					if (i + 4 > n) {
						throw ScriptException.at(source, i - 2, "illegal unicode escape");
					}
					try {
						text.append((char) Integer.parseInt(source.substring(i, i + 4), 16));
					} catch (NumberFormatException e) {
						throw ScriptException.at(source, i - 2, "illegal unicode escape");
					}
					i += 4;
				}
				default -> throw ScriptException.at(source, i - 2, "illegal escape character '\\" + escape + "'");
			}
		}
		tokens.add(new Token(Kind.STRING, source.substring(start, i), text.toString(), start));
		return i;
	}
}
