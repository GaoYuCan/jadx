package jadx.mcp.format;

import org.jetbrains.annotations.Nullable;

/**
 * One row in the {@link RefTable}: a stable handle ({@link #refId}) for a single symbol reference inside a class's
 * decompiled source, plus everything the LLM might want to know about it without requesting another tool call.
 *
 * <p>Coordinates ({@code line}, {@code col}, {@code defPos}) all refer to the <b>raw</b> jadx output (the same
 * coordinate space that {@code search_code} and {@code xrefs_to} use). Decoration done by
 * {@link LineNumberPrefixer} / {@link InlineRefAnnotator} never changes line numbers.
 */
public final class RefEntry {

	public enum Kind { CLASS, METHOD, FIELD, PACKAGE, VAR }

	private final String refId;
	private final int line;
	private final int col;
	private final int defPos; // position of the reference itself in raw code
	private final Kind kind;
	private final String targetFqn; // owning class FQN (or package for PACKAGE)
	private final @Nullable String targetMember; // method/field name; null for CLASS/PACKAGE
	private final @Nullable String targetDescriptor; // jvm descriptor like "(I)V"; null when not a method or unavailable
	private final String snippet; // ±20 chars of raw source around the reference, for disambiguation in nested calls

	public RefEntry(String refId, int line, int col, int defPos, Kind kind,
			String targetFqn, @Nullable String targetMember, @Nullable String targetDescriptor, String snippet) {
		this.refId = refId;
		this.line = line;
		this.col = col;
		this.defPos = defPos;
		this.kind = kind;
		this.targetFqn = targetFqn;
		this.targetMember = targetMember;
		this.targetDescriptor = targetDescriptor;
		this.snippet = snippet;
	}

	public String refId() {
		return refId;
	}

	public int line() {
		return line;
	}

	public int col() {
		return col;
	}

	public int defPos() {
		return defPos;
	}

	public Kind kind() {
		return kind;
	}

	public String targetFqn() {
		return targetFqn;
	}

	public @Nullable String targetMember() {
		return targetMember;
	}

	public @Nullable String targetDescriptor() {
		return targetDescriptor;
	}

	public String snippet() {
		return snippet;
	}
}
