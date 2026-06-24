package jadx.mcp.search;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jadx.mcp.util.ToolException;

/**
 * Pure-string search primitive shared by {@code search_code} and {@code search_resource}.
 * <p>
 * Mirrors the three-branch strategy that {@code jadx.gui.search.ISearchMethod#build} uses:
 * regex (compiled once with {@code CASE_INSENSITIVE} flag if requested), case-insensitive literal,
 * or plain literal. Always operates on the <em>raw</em> source text so coordinates line up with the
 * other tools (see "layering principle" in the architecture doc).
 */
public final class SearchEngine {

	private final Pattern pattern; // null when not regex
	private final String query;
	private final boolean ignoreCase;

	public SearchEngine(String query, boolean useRegex, boolean ignoreCase) {
		this.query = query;
		this.ignoreCase = ignoreCase;
		if (useRegex) {
			try {
				this.pattern = Pattern.compile(query, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
			} catch (Exception e) {
				throw ToolException.invalidArg("query", "invalid regex: " + e.getMessage());
			}
		} else {
			this.pattern = null;
		}
	}

	/** Returns the offset of the next match at or after {@code start}, or {@code -1} if none. */
	public int find(String input, int start) {
		if (pattern != null) {
			Matcher m = pattern.matcher(input);
			if (m.find(start)) {
				return m.start();
			}
			return -1;
		}
		if (ignoreCase) {
			return indexOfIgnoreCase(input, query, start);
		}
		return input.indexOf(query, start);
	}

	private static int indexOfIgnoreCase(String haystack, String needle, int start) {
		if (needle.isEmpty()) {
			return start;
		}
		int max = haystack.length() - needle.length();
		for (int i = start; i <= max; i++) {
			if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
				return i;
			}
		}
		return -1;
	}
}
