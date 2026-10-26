package net.users.variables;

/** A syntax error (thrown while a pack loads) or a type/lookup error (thrown while code runs). */
public class ScriptException extends RuntimeException {
	public ScriptException(String message) {
		super(message);
	}

	/** Code written as a JSON array is one line per element, so "line N" points at element N. */
	static ScriptException at(String source, int position, String message) {
		if (position >= source.length()) {
			return new ScriptException(message + " (at the end of the code)");
		}
		int lineStart = source.lastIndexOf('\n', position - 1) + 1;
		int lineEnd = source.indexOf('\n', position);
		boolean multiline = source.indexOf('\n') >= 0;
		if (lineEnd < 0) {
			lineEnd = source.length();
		}
		int line = 1;
		for (int i = 0; i < lineStart; i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		String where = (multiline ? "line " + line + ", " : "") + "column " + (position - lineStart + 1);
		String near = source.substring(position, Math.min(lineEnd, position + 20));
		return new ScriptException(message + " (" + where + ", near \"" + near + "\")");
	}
}
