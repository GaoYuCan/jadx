package jadx.mcp.tools;

import org.jetbrains.annotations.Nullable;

/**
 * Parsed form of the {@code target} string used by {@code decompile_code} / {@code disassemble}.
 *
 * <pre>
 *   com.foo.A             -> classFqn=com.foo.A, member=null
 *   com.foo.A$Inner       -> classFqn=com.foo.A$Inner, member=null
 *   com.foo.A#bar         -> classFqn=com.foo.A, member=bar, descriptor=null  (any overload)
 *   com.foo.A#bar(I)V     -> classFqn=com.foo.A, member=bar, descriptor=(I)V (precise)
 *   com.foo.A#&lt;init&gt;()V    -> classFqn=com.foo.A, member=&lt;init&gt;, descriptor=()V
 * </pre>
 */
public final class Target {
	public enum Kind { CLASS, METHOD }

	private final Kind kind;
	private final String classFqn;
	private final @Nullable String memberName;
	private final @Nullable String memberDescriptor; // jvm descriptor like "(I)V" -- without name prefix
	private final @Nullable String shortId; // "name(args)ret" -- only when descriptor present

	private Target(Kind kind, String classFqn, @Nullable String memberName,
			@Nullable String memberDescriptor, @Nullable String shortId) {
		this.kind = kind;
		this.classFqn = classFqn;
		this.memberName = memberName;
		this.memberDescriptor = memberDescriptor;
		this.shortId = shortId;
	}

	public Kind kind() {
		return kind;
	}

	public String classFqn() {
		return classFqn;
	}

	public @Nullable String memberName() {
		return memberName;
	}

	public @Nullable String memberDescriptor() {
		return memberDescriptor;
	}

	/** Method short-id formatted as {@code name(args)ret}, suitable for {@code ClassNode#searchMethodByShortId}. */
	public @Nullable String shortId() {
		return shortId;
	}

	public boolean isClass() {
		return kind == Kind.CLASS;
	}

	public boolean isMethod() {
		return kind == Kind.METHOD;
	}

	public boolean hasDescriptor() {
		return memberDescriptor != null;
	}

	@Override
	public String toString() {
		if (kind == Kind.CLASS) {
			return classFqn;
		}
		return classFqn + "#" + memberName + (memberDescriptor != null ? memberDescriptor : "");
	}

	public static Target ofClass(String classFqn) {
		return new Target(Kind.CLASS, classFqn, null, null, null);
	}

	public static Target ofMethod(String classFqn, String name, @Nullable String descriptor) {
		String shortId = descriptor == null ? null : name + descriptor;
		return new Target(Kind.METHOD, classFqn, name, descriptor, shortId);
	}
}
