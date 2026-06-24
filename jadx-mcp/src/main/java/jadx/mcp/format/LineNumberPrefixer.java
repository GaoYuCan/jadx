package jadx.mcp.format;

/**
 * Prepends a fixed-width line number gutter to each line: {@code "   42| <line content>"}.
 * <p>
 * Pure decoration: line count is preserved, so an LLM that picks a line number from this output can use the
 * same number in {@code search_code} hits or {@code xrefs_to} responses without any translation.
 */
public final class LineNumberPrefixer {

	private LineNumberPrefixer() {
	}

	/** Optionally render only the given inclusive {@code [fromLine, toLine]} range. {@code 0 / Integer.MAX_VALUE} -> all. */
	public static String prefix(String code) {
		return prefix(code, 1, Integer.MAX_VALUE);
	}

	public static String prefix(String code, int fromLine, int toLine) {
		int totalLines = countLines(code);
		int width = Math.max(2, Integer.toString(Math.min(toLine == Integer.MAX_VALUE ? totalLines : toLine, totalLines))
				.length());
		StringBuilder out = new StringBuilder(code.length() + totalLines * (width + 3));
		int line = 1;
		int i = 0;
		boolean lineStart = true;
		while (i <= code.length()) {
			if (lineStart && line >= fromLine && line <= toLine) {
				appendNumber(out, line, width);
				out.append("| ");
				lineStart = false;
			}
			if (i == code.length()) {
				break;
			}
			char c = code.charAt(i);
			if (line >= fromLine && line <= toLine) {
				out.append(c);
			}
			if (c == '\n') {
				line++;
				lineStart = true;
				if (line > toLine) {
					break;
				}
			}
			i++;
		}
		return out.toString();
	}

	private static int countLines(String code) {
		int n = 1;
		for (int i = 0; i < code.length(); i++) {
			if (code.charAt(i) == '\n') {
				n++;
			}
		}
		return n;
	}

	private static void appendNumber(StringBuilder sb, int n, int width) {
		String s = Integer.toString(n);
		for (int i = s.length(); i < width; i++) {
			sb.append(' ');
		}
		sb.append(s);
	}
}
