package jadx.mcp.format;

import java.util.List;

/**
 * Inserts a Java block comment immediately after each annotated symbol's start position so that
 * <pre>obj.func1(obj2.func2(), obj3.func3())</pre>
 * becomes
 * <pre>obj.func1/*&rarr;A#R42*&#47;(obj2.func2/*&rarr;B#R43*&#47;(), obj3.func3/*&rarr;C#R44*&#47;())</pre>
 *
 * <p>Each annotation marker is pinned to its symbol, so deeply nested calls remain unambiguous (the
 * "row-tail summary" approach can't disambiguate when a single line has multiple references).
 *
 * <p>Comments use Java block syntax so they neither break the source nor add new lines: line numbers
 * stay identical to the raw output, which keeps {@code search_code} / {@code xrefs_to} coordinates
 * valid in the annotated view as well.
 */
public final class InlineRefAnnotator {

	private InlineRefAnnotator() {
	}

	public static String annotate(String rawCode, String classFqn, List<RefEntry> entries) {
		if (entries.isEmpty()) {
			return rawCode;
		}
		// Compute the current class's package once so we can shorten same-package targets.
		String currentPkg = packageOf(classFqn);
		StringBuilder out = new StringBuilder(rawCode.length() + entries.size() * 24);
		int cursor = 0;
		// entries are in raw-offset ascending order (RefTableBuilder guarantees this); skip past identifier first
		for (RefEntry e : entries) {
			int defPos = e.defPos();
			if (defPos < cursor) {
				continue; // overlapping entries (rare): keep first
			}
			int identEnd = endOfIdentifier(rawCode, defPos);
			out.append(rawCode, cursor, identEnd);
			out.append("/*->").append(shortenTarget(e, currentPkg)).append('#').append(e.refId()).append("*/");
			cursor = identEnd;
		}
		out.append(rawCode, cursor, rawCode.length());
		return out.toString();
	}

	private static int endOfIdentifier(String code, int start) {
		int i = start;
		int n = code.length();
		while (i < n) {
			char c = code.charAt(i);
			if (Character.isJavaIdentifierPart(c) || c == '$') {
				i++;
			} else {
				break;
			}
		}
		// if we landed on a non-identifier (e.g. punctuation or the start of an inserted block comment)
		// keep the marker exactly at defPos -- i == start in that case so the marker is inserted before the char.
		return i;
	}

	private static String shortenTarget(RefEntry e, String currentPkg) {
		String fqn = e.targetFqn();
		String shortClass = simpleName(fqn);
		String pkg = packageOf(fqn);
		StringBuilder b = new StringBuilder();
		// drop the package prefix only when same-package; otherwise keep FQN to avoid collisions across packages.
		if (pkg.equals(currentPkg)) {
			b.append(shortClass);
		} else {
			b.append(fqn);
		}
		if (e.targetMember() != null) {
			b.append('.').append(e.targetMember());
			if (e.targetDescriptor() != null) {
				b.append(e.targetDescriptor());
			}
		}
		return b.toString();
	}

	private static String packageOf(String fqn) {
		int lastDot = fqn.lastIndexOf('.');
		return lastDot < 0 ? "" : fqn.substring(0, lastDot);
	}

	private static String simpleName(String fqn) {
		int lastDot = fqn.lastIndexOf('.');
		return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
	}
}
