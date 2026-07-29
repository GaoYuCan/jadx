package jadx.mcp.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jadx.api.JavaClass;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

/**
 * Shared formatters for symbol-table entries (classes / methods / fields) used by every tool that
 * surfaces them to the MCP wire. Keeps the JSON shape consistent across {@code search_symbol},
 * {@code class_members}, {@code list_classes}, etc.
 *
 * <p>Output contract:
 * <ul>
 *   <li>{@code access} is always a string array of human-readable tokens, never a raw int.</li>
 *   <li>Aliases are reported as the primary {@code name} / {@code fqn}; raw bytecode names are
 *       emitted as {@code raw_*} sibling fields only when they differ.</li>
 *   <li>{@code target} strings are formatted such that they round-trip through
 *       {@link jadx.mcp.tools.TargetParser} and {@code decompile_code} / {@code xrefs_to} verbatim.</li>
 * </ul>
 */
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

	/**
	 * Standard hit shape for a class. Always includes {@code target}, {@code name}, {@code fqn};
	 * adds {@code raw_fqn} only when the alias differs from the raw name.
	 */
	public static Map<String, Object> classHit(JavaClass cls) {
		Map<String, Object> hit = new LinkedHashMap<>();
		String fqn = cls.getFullName();
		hit.put("target", fqn);
		hit.put("name", cls.getName());
		hit.put("fqn", fqn);
		String raw = cls.getRawName();
		if (!fqn.equals(raw)) {
			hit.put("raw_fqn", raw);
		}
		return hit;
	}

	/**
	 * Standard hit shape for a method. {@code short_id} is jadx's
	 * {@code name(jvmArgs)retDescriptor} form; {@code target = classFqn + '#' + short_id} is the
	 * canonical cross-tool reference.
	 */
	public static Map<String, Object> methodHit(JavaClass cls, MethodNode mth) {
		MethodInfo info = mth.getMethodInfo();
		String classFqn = cls.getFullName();
		String shortId = info.getShortId();
		Map<String, Object> hit = new LinkedHashMap<>();
		hit.put("target", classFqn + "#" + shortId);
		hit.put("class_fqn", classFqn);
		hit.put("name", info.getAlias());
		hit.put("short_id", shortId);
		if (!classFqn.equals(cls.getRawName())) {
			hit.put("raw_class_fqn", cls.getRawName());
		}
		String raw = info.getName();
		if (!raw.equals(info.getAlias())) {
			hit.put("raw_name", raw);
		}
		return hit;
	}

	/** Standard hit shape for a field. {@code target = classFqn + '#' + alias}. */
	public static Map<String, Object> fieldHit(JavaClass cls, FieldNode fld) {
		String classFqn = cls.getFullName();
		Map<String, Object> hit = new LinkedHashMap<>();
		hit.put("target", classFqn + "#" + fld.getAlias());
		hit.put("class_fqn", classFqn);
		hit.put("name", fld.getAlias());
		String raw = fld.getName();
		if (!raw.equals(fld.getAlias())) {
			hit.put("raw_name", raw);
		}
		return hit;
	}
}
