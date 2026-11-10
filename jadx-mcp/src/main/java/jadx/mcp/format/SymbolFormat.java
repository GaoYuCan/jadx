package jadx.mcp.format;

import java.util.ArrayList;
import java.util.List;

import jadx.core.dex.info.AccessInfo;

/** Shared class-kind and access-flag formatting for symbol tools. */
public final class SymbolFormat {

	private SymbolFormat() {
	}

	/** Lower-case kind label for a class: {@code class | interface | enum | annotation}. */
	public static String classKind(AccessInfo acc) {
		if (acc.isAnnotation()) {
			return "annotation";
		}
		if (acc.isInterface()) {
			return "interface";
		}
		if (acc.isEnum()) {
			return "enum";
		}
		return "class";
	}

	/**
	 * Convert an {@link AccessInfo} into the wire-friendly token list documented in the
	 * {@code search_symbol filter.access} schema (e.g. {@code ["public", "static", "final"]}).
	 * Visibility tokens always appear first; type-specific tokens (synchronized/bridge/varargs for
	 * methods, volatile/transient/enum for fields, interface/enum/annotation for classes) are appended.
	 */
	public static List<String> accessTokens(AccessInfo acc, AccessInfo.AFType type) {
		List<String> out = new ArrayList<>(4);
		if (acc.isPublic()) {
			out.add("public");
		}
		if (acc.isProtected()) {
			out.add("protected");
		}
		if (acc.isPrivate()) {
			out.add("private");
		}
		if (acc.isStatic()) {
			out.add("static");
		}
		if (acc.isFinal()) {
			out.add("final");
		}
		if (acc.isAbstract()) {
			out.add("abstract");
		}
		if (acc.isSynthetic()) {
			out.add("synthetic");
		}
		switch (type) {
			case METHOD:
				if (acc.isNative()) {
					out.add("native");
				}
				if (acc.isSynchronized()) {
					out.add("synchronized");
				}
				if (acc.isBridge()) {
					out.add("bridge");
				}
				if (acc.isVarArgs()) {
					out.add("varargs");
				}
				break;
			case FIELD:
				if (acc.isVolatile()) {
					out.add("volatile");
				}
				if (acc.isTransient()) {
					out.add("transient");
				}
				if (acc.isEnum()) {
					out.add("enum");
				}
				break;
			case CLASS:
				if (acc.isInterface()) {
					out.add("interface");
				}
				if (acc.isEnum()) {
					out.add("enum");
				}
				if (acc.isAnnotation()) {
					out.add("annotation");
				}
				break;
		}
		return out;
	}
}
