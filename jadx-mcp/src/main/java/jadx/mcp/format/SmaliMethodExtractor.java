package jadx.mcp.format;

import org.jetbrains.annotations.Nullable;

/**
 * Extracts a single method block (from {@code .method} to its matching {@code .end method}) from a class's
 * full smali listing as produced by {@link jadx.api.JavaClass#getSmali()}.
 *
 * <p>Note this is intentionally <i>different</i> from
 * {@link jadx.gui.search.SmaliMethodLineParser}, which finds the nearest {@code .method} short-id around a
 * <i>position</i> (used for surfacing context in search results). Here we have a known short-id and want
 * the whole method body.
 */
public final class SmaliMethodExtractor {

	private SmaliMethodExtractor() {
	}

	/**
	 * @param smali full class smali text
	 * @param shortId method shortId in jvm form, e.g. {@code "bar(I)V"}
	 * @return the slice of {@code smali} containing the {@code .method ... .end method} block, or {@code null}
	 *         if no matching method header is found
	 */
	public static @Nullable String extract(String smali, String shortId) {
		int from = 0;
		while (true) {
			int hdr = smali.indexOf(".method", from);
			if (hdr < 0) {
				return null;
			}
			// a .method line might be inlined inside a comment; trust raw smali (jadx never produces such comments)
			int lineEnd = smali.indexOf('\n', hdr);
			if (lineEnd < 0) {
				return null;
			}
			String headerLine = smali.substring(hdr, lineEnd);
			if (headerLineMatches(headerLine, shortId)) {
				int end = smali.indexOf(".end method", lineEnd);
				if (end < 0) {
					return null;
				}
				int endLine = smali.indexOf('\n', end);
				int endIncl = endLine < 0 ? smali.length() : endLine;
				return smali.substring(hdr, endIncl);
			}
			from = lineEnd + 1;
		}
	}

	/** Returns the start line (1-based) of the method header within {@code smali}, or {@code -1} if not found. */
	public static int findHeaderLine(String smali, String shortId) {
		int from = 0;
		int line = 1;
		while (true) {
			int hdr = smali.indexOf(".method", from);
			if (hdr < 0) {
				return -1;
			}
			int prevNl = smali.lastIndexOf('\n', hdr);
			while (from <= prevNl) {
				if (smali.charAt(from) == '\n') {
					line++;
				}
				from++;
			}
			int lineEnd = smali.indexOf('\n', hdr);
			if (lineEnd < 0) {
				return -1;
			}
			String headerLine = smali.substring(hdr, lineEnd);
			if (headerLineMatches(headerLine, shortId)) {
				return line;
			}
			line++;
			from = lineEnd + 1;
		}
	}

	private static boolean headerLineMatches(String headerLine, String shortId) {
		// header looks like ".method <flags> name(args)retType"; locate the '(' to anchor matching
		int lp = headerLine.indexOf('(');
		if (lp < 0) {
			return false;
		}
		// find the start of the symbol token: walk back from '(' over identifier characters
		int nameStart = lp;
		while (nameStart > 0) {
			char c = headerLine.charAt(nameStart - 1);
			if (Character.isJavaIdentifierPart(c) || c == '$' || c == '<' || c == '>') {
				nameStart--;
			} else {
				break;
			}
		}
		String token = headerLine.substring(nameStart).trim();
		return token.equals(shortId);
	}
}
