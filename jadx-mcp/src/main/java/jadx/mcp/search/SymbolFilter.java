package jadx.mcp.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import jadx.api.JavaClass;
import jadx.api.plugins.input.data.AccessFlags;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.data.attributes.types.AnnotationsAttr;
import jadx.core.dex.attributes.IAttributeNode;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.mcp.format.SymbolFormat;
import jadx.mcp.util.ToolException;

/**
 * Parsed structural filter for {@code search_symbol}. Owns the filter spec, its parser, and the
 * predicate logic — kept here (instead of inside {@code SearchSymbolTool}) so the tool class is
 * about request handling and the filter rules live in one inspectable place.
 *
 * <p>Validation happens up front in {@link #parse} so the tool can fail fast with a precise
 * {@code INVALID_ARG} before any iteration starts.
 *
 * <p>Matchers are split into the cheapest-first order:
 * <ol>
 *   <li>{@link #matchesPackagePrefix} — string {@code startsWith} on the FQN,</li>
 *   <li>{@link #matchesClassFilter} for kind=class — a few field reads + bit-test,</li>
 *   <li>{@link #matchesAccessMask} for members — single bit-test,</li>
 *   <li>{@link #matchesAnnotation} — one map lookup.</li>
 * </ol>
 * Name regex / substring is the last (and most expensive) check, done by the tool itself.
 */
public final class SymbolFilter {

	public final @Nullable String packagePrefix;
	public final @Nullable String classFqn;
	public final boolean includeRawNames;
	public final int requiredAccessMask;
	public final int forbiddenAccessMask;
	public final @Nullable String annotation;
	public final @Nullable String classKind; // class | interface | enum | annotation
	public final @Nullable String superclass;
	public final boolean superclassTransitive;
	public final @Nullable String iface;
	public final boolean interfaceTransitive;

	private SymbolFilter(@Nullable String packagePrefix, @Nullable String classFqn, boolean includeRawNames,
			int requiredAccessMask, int forbiddenAccessMask,
			@Nullable String annotation, @Nullable String classKind,
			@Nullable String superclass, boolean superclassTransitive,
			@Nullable String iface, boolean interfaceTransitive) {
		this.packagePrefix = packagePrefix;
		this.classFqn = classFqn;
		this.includeRawNames = includeRawNames;
		this.requiredAccessMask = requiredAccessMask;
		this.forbiddenAccessMask = forbiddenAccessMask;
		this.annotation = annotation;
		this.classKind = classKind;
		this.superclass = superclass;
		this.superclassTransitive = superclassTransitive;
		this.iface = iface;
		this.interfaceTransitive = interfaceTransitive;
	}

	/** {@code true} when no constraint is active (caller must require either query or filter). */
	public boolean isEmpty() {
		return packagePrefix == null
				&& classFqn == null
				&& !includeRawNames
				&& requiredAccessMask == 0
				&& forbiddenAccessMask == 0
				&& annotation == null
				&& classKind == null
				&& superclass == null
				&& iface == null;
	}

	// ---------- parsing ----------

	/**
	 * Parse a {@code filter} JSON object. {@code kind} is the {@code search_symbol} kind so that
	 * misuse like {@code superclass} on {@code kind=method} fails cleanly here instead of returning
	 * a confusing zero-hits result.
	 */
	public static SymbolFilter parse(@Nullable Map<String, Object> filterMap, String kind) {
		if (filterMap == null) {
			return new SymbolFilter(null, null, false, 0, 0, null, null, null, false, null, false);
		}
		String packagePrefix = strOrNull(filterMap, "package_prefix");
		String classFqn = strOrNull(filterMap, "class_fqn");
		boolean includeRawNames = boolOrFalse(filterMap, "include_raw_names");
		String annotation = strOrNull(filterMap, "annotation");
		String classKind = strOrNull(filterMap, "class_kind");
		String superclass = strOrNull(filterMap, "superclass");
		boolean superclassTransitive = boolOrFalse(filterMap, "superclass_transitive");
		String iface = strOrNull(filterMap, "interface");
		boolean interfaceTransitive = boolOrFalse(filterMap, "interface_transitive");

		int[] accMasks = parseAccessTokens(filterMap.get("access"));

		// Validate kind-restricted fields up front so users see one clean error per misuse.
		boolean isClass = "class".equals(kind);
		if (!isClass) {
			if (classKind != null) {
				throw ToolException.invalidArg("filter.class_kind", "only valid when kind=class");
			}
			if (superclass != null) {
				throw ToolException.invalidArg("filter.superclass", "only valid when kind=class");
			}
			if (iface != null) {
				throw ToolException.invalidArg("filter.interface", "only valid when kind=class");
			}
		} else if (classFqn != null) {
			throw ToolException.invalidArg("filter.class_fqn",
					"only valid when kind=method or kind=field");
		}
		if (classKind != null) {
			classKind = classKind.toLowerCase(Locale.ROOT);
			if (!classKind.equals("class") && !classKind.equals("interface")
					&& !classKind.equals("enum") && !classKind.equals("annotation")) {
				throw ToolException.invalidArg("filter.class_kind",
						"must be one of class | interface | enum | annotation");
			}
		}
		if (annotation != null && !isInternalAnnotationFqn(annotation)) {
			throw ToolException.invalidArg("filter.annotation",
					"must be in dex internal form, e.g. 'Landroid/webkit/JavascriptInterface;'");
		}
		return new SymbolFilter(packagePrefix, classFqn, includeRawNames,
				accMasks[0], accMasks[1],
				annotation, classKind, superclass, superclassTransitive, iface, interfaceTransitive);
	}

	/** {@code (requiredMask, forbiddenMask)} pair from the {@code access} token list. */
	private static int[] parseAccessTokens(@Nullable Object accessRaw) {
		int required = 0;
		int forbidden = 0;
		if (accessRaw == null) {
			return new int[] { 0, 0 };
		}
		List<String> tokens;
		if (accessRaw instanceof List<?> l) {
			tokens = new ArrayList<>(l.size());
			for (Object o : l) {
				if (!(o instanceof String s)) {
					throw ToolException.invalidArg("filter.access", "must be an array of strings");
				}
				tokens.add(s);
			}
		} else if (accessRaw instanceof String s) {
			tokens = Arrays.asList(s.split("\\s*,\\s*"));
		} else {
			throw ToolException.invalidArg("filter.access", "must be an array of strings");
		}
		for (String token : tokens) {
			String t = token.trim();
			if (t.isEmpty()) {
				continue;
			}
			boolean negated = t.startsWith("!");
			if (negated) {
				t = t.substring(1).trim();
			}
			int mask = accessFlagMask(t);
			if (mask == 0) {
				throw ToolException.invalidArg("filter.access", "unknown flag token: '" + token + "'");
			}
			if (negated) {
				forbidden |= mask;
			} else {
				required |= mask;
			}
		}
		return new int[] { required, forbidden };
	}

	// ---------- matchers ----------

	/** Cheap first-pass filter: every kind needs the class to live under the requested package. */
	public boolean matchesPackagePrefix(JavaClass cls) {
		if (packagePrefix == null) {
			return true;
		}
		if (cls.getFullName().startsWith(packagePrefix)) {
			return true;
		}
		return includeRawNames && cls.getRawName().startsWith(packagePrefix);
	}

	/** Apply every class-only filter: access, class_kind, superclass, interface, annotation. */
	public boolean matchesClassFilter(JavaClass cls,
			@Nullable Set<String> superSubtypes, @Nullable Set<String> interfaceImpls) {
		ClassNode cn = cls.getClassNode();
		AccessInfo acc = cn.getAccessFlags();
		if (!matchesAccessMask(acc.rawValue())) {
			return false;
		}
		if (classKind != null && !SymbolFormat.classKind(acc).equals(classKind)) {
			return false;
		}
		if (superclass != null && !classExtends(cn, superclass, superclassTransitive, superSubtypes)) {
			return false;
		}
		if (iface != null && !classImplements(cn, iface, interfaceTransitive, interfaceImpls)) {
			return false;
		}
		return matchesAnnotation(cn);
	}

	/** Member-level access bit-test. */
	public boolean matchesAccessMask(int rawAcc) {
		if ((rawAcc & requiredAccessMask) != requiredAccessMask) {
			return false;
		}
		return (rawAcc & forbiddenAccessMask) == 0;
	}

	/** Annotation presence on any {@link IAttributeNode} (class / method / field). */
	public boolean matchesAnnotation(IAttributeNode node) {
		if (annotation == null) {
			return true;
		}
		AnnotationsAttr aList = node.get(JadxAttrType.ANNOTATION_LIST);
		if (aList == null) {
			return false;
		}
		return aList.get(annotation) != null;
	}

	private static boolean classExtends(ClassNode cn, String wantedDot,
			boolean transitive, @Nullable Set<String> transitiveSubtypes) {
		if (transitive && transitiveSubtypes != null) {
			return transitiveSubtypes.contains(cn.getClassInfo().getType().getObject());
		}
		ArgType sup = cn.getSuperClass();
		return sup != null && sup.isObject() && sup.getObject().equals(wantedDot);
	}

	private static boolean classImplements(ClassNode cn, String wantedDot,
			boolean transitive, @Nullable Set<String> transitiveImpls) {
		if (transitive && transitiveImpls != null) {
			return transitiveImpls.contains(cn.getClassInfo().getType().getObject());
		}
		for (ArgType iface : cn.getInterfaces()) {
			if (iface.isObject() && iface.getObject().equals(wantedDot)) {
				return true;
			}
		}
		return false;
	}

	// ---------- helpers ----------

	private static @Nullable String strOrNull(Map<String, Object> map, String key) {
		Object v = map.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof String s) {
			return s.isEmpty() ? null : s;
		}
		throw ToolException.invalidArg("filter." + key, "must be a string");
	}

	private static boolean boolOrFalse(Map<String, Object> map, String key) {
		Object v = map.get(key);
		if (v == null) {
			return false;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		if (v instanceof String s) {
			return Boolean.parseBoolean(s);
		}
		throw ToolException.invalidArg("filter." + key, "must be a boolean");
	}

	private static int accessFlagMask(String token) {
		switch (token.toLowerCase(Locale.ROOT)) {
			case "public": return AccessFlags.PUBLIC;
			case "private": return AccessFlags.PRIVATE;
			case "protected": return AccessFlags.PROTECTED;
			case "static": return AccessFlags.STATIC;
			case "final": return AccessFlags.FINAL;
			case "abstract": return AccessFlags.ABSTRACT;
			case "native": return AccessFlags.NATIVE;
			case "synthetic": return AccessFlags.SYNTHETIC;
			case "volatile": return AccessFlags.VOLATILE;
			case "transient": return AccessFlags.TRANSIENT;
			case "varargs": return AccessFlags.VARARGS;
			case "bridge": return AccessFlags.BRIDGE;
			case "interface": return AccessFlags.INTERFACE;
			case "enum": return AccessFlags.ENUM;
			case "annotation": return AccessFlags.ANNOTATION;
			default: return 0;
		}
	}

	private static boolean isInternalAnnotationFqn(String s) {
		if (s.length() < 3) {
			return false;
		}
		if (s.charAt(0) != 'L' || s.charAt(s.length() - 1) != ';') {
			return false;
		}
		// dot-form FQN would mean LLM forgot the slashes; reject so users see a clear error.
		return s.indexOf('.') < 0;
	}
}
