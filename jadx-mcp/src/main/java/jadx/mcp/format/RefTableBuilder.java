package jadx.mcp.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jadx.api.ICodeInfo;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.metadata.ICodeAnnotation;

/**
 * Scans {@link ICodeInfo#getCodeMetadata()} once and produces a {@link RefTable} with stable {@code ref_id}s.
 *
 * <h3>Filtering</h3>
 * Only "interesting" references survive into the table. We keep CLASS / METHOD / FIELD references and skip:
 * <ul>
 *   <li>{@code OFFSET} / {@code END} / {@code DECLARATION} / {@code VAR} / {@code VAR_REF} (structural noise)</li>
 *   <li>References whose target is the class being decompiled (same-class self-references add no info)</li>
 *   <li>References to {@code java.lang.*} / {@code kotlin.*} (well-known stdlib clutter)</li>
 * </ul>
 *
 * <h3>Stability</h3>
 * Ref IDs are assigned in raw-offset order ({@code R0}, {@code R1}, ...), so re-scanning the same {@link ICodeInfo}
 * always produces the same IDs.
 */
public final class RefTableBuilder {

	private static final int SNIPPET_RADIUS = 24;

	private RefTableBuilder() {
	}

	public static RefTable build(JadxDecompiler decompiler, JavaClass javaClass) {
		ICodeInfo codeInfo = javaClass.getCodeInfo();
		String code = codeInfo.getCodeStr();
		String classFqn = javaClass.getFullName();
		List<RefEntry> entries = new ArrayList<>();
		if (!codeInfo.hasMetadata()) {
			return new RefTable(classFqn, entries);
		}
		Map<Integer, ICodeAnnotation> map = codeInfo.getCodeMetadata().getAsMap();
		// stable order by position
		Map<Integer, ICodeAnnotation> sorted = new TreeMap<>(map);

		// pre-compute line numbers in O(N) by walking the string once with a sweep cursor over annotations
		LineIndex lines = new LineIndex(code);
		int counter = 0;
		for (Map.Entry<Integer, ICodeAnnotation> e : sorted.entrySet()) {
			int pos = e.getKey();
			ICodeAnnotation ann = e.getValue();
			if (!isKept(ann)) {
				continue;
			}
			JavaNode node = decompiler.getJavaNodeByCodeAnnotation(codeInfo, ann);
			if (node == null) {
				continue;
			}
			RefEntry.Kind kind = kindOf(node);
			if (kind == null) {
				continue;
			}
			String targetFqn;
			String targetMember = null;
			String targetDescriptor = null;
			if (node instanceof JavaClass jc) {
				targetFqn = jc.getFullName();
			} else if (node instanceof JavaMethod jm) {
				targetFqn = jm.getDeclaringClass().getFullName();
				targetMember = jm.getName();
				String shortId = jm.getMethodNode().getMethodInfo().getShortId();
				int lp = shortId.indexOf('(');
				targetDescriptor = lp >= 0 ? shortId.substring(lp) : null;
			} else if (node instanceof JavaField jf) {
				targetFqn = jf.getDeclaringClass().getFullName();
				targetMember = jf.getName();
			} else {
				targetFqn = node.getFullName();
			}
			if (shouldSkipTarget(classFqn, targetFqn)) {
				continue;
			}
			lines.advanceTo(pos);
			int line = lines.line();
			int col = pos - lines.lineStart();
			String snippet = sliceSnippet(code, pos);
			String refId = "R" + counter;
			counter++;
			entries.add(new RefEntry(refId, line, col, pos, kind, targetFqn, targetMember, targetDescriptor, snippet));
		}
		return new RefTable(classFqn, entries);
	}

	private static boolean isKept(ICodeAnnotation ann) {
		switch (ann.getAnnType()) {
			case CLASS:
			case METHOD:
			case FIELD:
				return true;
			default:
				return false;
		}
	}

	private static RefEntry.Kind kindOf(JavaNode node) {
		if (node instanceof JavaClass) {
			return RefEntry.Kind.CLASS;
		}
		if (node instanceof JavaMethod) {
			return RefEntry.Kind.METHOD;
		}
		if (node instanceof JavaField) {
			return RefEntry.Kind.FIELD;
		}
		return null;
	}

	private static boolean shouldSkipTarget(String classFqn, String targetFqn) {
		if (targetFqn.equals(classFqn)) {
			return true;
		}
		if (targetFqn.startsWith("java.lang.")) {
			// skip plain java.lang stuff but keep nested types like java.lang.reflect.*
			int lastDot = targetFqn.lastIndexOf('.');
			return lastDot == "java.lang".length();
		}
		if (targetFqn.startsWith("kotlin.")) {
			return true;
		}
		return false;
	}

	private static String sliceSnippet(String code, int pos) {
		int start = Math.max(0, pos - SNIPPET_RADIUS);
		int end = Math.min(code.length(), pos + SNIPPET_RADIUS);
		String s = code.substring(start, end);
		// flatten newlines so the snippet stays one-line in JSON; readers don't care about exact whitespace.
		return s.replace('\n', ' ').replace('\r', ' ');
	}

	/**
	 * One-pass forward-only line/column tracker over the source string. Callers must invoke
	 * {@link #advanceTo(int)} with monotonically non-decreasing positions.
	 */
	private static final class LineIndex {
		private final String code;
		private int cursor = 0;
		private int line = 1;
		private int lineStart = 0;

		LineIndex(String code) {
			this.code = code;
		}

		void advanceTo(int pos) {
			while (cursor < pos && cursor < code.length()) {
				if (code.charAt(cursor) == '\n') {
					line++;
					lineStart = cursor + 1;
				}
				cursor++;
			}
		}

		int line() {
			return line;
		}

		int lineStart() {
			return lineStart;
		}
	}
}
