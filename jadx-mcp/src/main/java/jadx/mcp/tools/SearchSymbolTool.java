package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.JavaClass;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.mcp.JadxSession;
import jadx.mcp.format.SymbolFormat;
import jadx.mcp.search.SearchEngine;
import jadx.mcp.search.SymbolFilter;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code search_symbol} tool: fast metadata-only name lookup with structured filters.
 *
 * <p>Top-level params control HOW the name is matched (regex / case / full-name) and pagination.
 * Structural constraints (package, class scope, raw-name fallback, annotation, supertype, access flags,
 * class kind) live under one nested {@code filter} object — keeps the schema flat for an LLM regardless
 * of how many filters are added later.
 *
 * <p>Filter parsing and predicate logic live in {@link SymbolFilter}; this class is just the request
 * handler / iteration loop.
 *
 * <p>The {@code annotation} filter applies to whichever node {@code kind} selects (class, method, or
 * field) because all three nodes store annotations under the same
 * {@link jadx.api.plugins.input.data.attributes.JadxAttrType#ANNOTATION_LIST} key.
 *
 * <p>Each hit carries a ready-to-use {@code target} string ({@code com.foo.A} / {@code com.foo.A#bar} /
 * {@code com.foo.A#bar(I)V}) that can be pasted straight into {@code decompile_code}, {@code disassemble},
 * {@code xrefs_to}, etc. — no second lookup needed.
 */
public final class SearchSymbolTool extends AbstractTool {

	public SearchSymbolTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "search_symbol";
	}

	@Override
	public String description() {
		return "Fast metadata-only symbol search over classes / methods / fields — no decompilation, no smali. "
				+ "Top-level params drive WHAT to search and HOW to match the name; structural constraints "
				+ "all live under `filter`. The `annotation` filter applies to whichever node `kind` selects "
				+ "(class / method / field) — handy for sweeping all sites of an annotation-driven framework.\n\n"
				+ "Examples:\n"
				+ "  // every @JavascriptInterface method in the APK\n"
				+ "  kind=method, filter={\"annotation\": \"Landroid/webkit/JavascriptInterface;\"}\n"
				+ "  // every Retrofit endpoint\n"
				+ "  kind=method, filter={\"annotation\": \"Lretrofit2/http/POST;\"}\n"
				+ "  // every Kotlin class\n"
				+ "  kind=class, filter={\"annotation\": \"Lkotlin/Metadata;\"}\n"
				+ "  // every @SerializedName field\n"
				+ "  kind=field, filter={\"annotation\": \"Lcom/google/gson/annotations/SerializedName;\"}\n"
				+ "  // every public, non-abstract Activity subclass\n"
				+ "  kind=class, filter={\"superclass\": \"android.app.Activity\", "
				+ "\"superclass_transitive\": true, \"access\": [\"public\", \"!abstract\"]}\n"
				+ "  // a single class's onCreate\n"
				+ "  kind=method, query=\"onCreate\", filter={\"class_fqn\": \"com.foo.Bar\"}\n\n"
				+ "Each hit returns a ready-to-use `target` string (e.g. 'com.foo.A#bar(I)V') accepted "
				+ "verbatim by `decompile_code`, `disassemble`, `xrefs_to`, etc.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.enumString("kind", "What to scan.", true, "class", "method", "field")
				.string("query",
						"Optional substring or regex to match against the symbol name. Omit when filtering "
								+ "by structural attributes alone (e.g. all @JavascriptInterface methods).",
						false)
				.bool("regex", "Treat `query` as a Java regex. Default false.", false)
				.bool("ignore_case", "Case-insensitive matching. Default false.", false)
				.bool("match_full_name",
						"Match against the full FQN (e.g. 'com.foo.A.bar') instead of the simple name. Default false.",
						false)
				.integer("max_results", "Max hits to return. Default 200.", false)
				.integer("offset", "Pagination cursor. Default 0.", false)
				.object("filter",
						"Optional structural constraints. All sub-fields are optional and combine with AND.",
						false,
						SearchSymbolTool::buildFilterSchema)
				.build();
	}

	private static void buildFilterSchema(SchemaBuilder b) {
		b.string("package_prefix",
				"Only consider classes whose FQN starts with this prefix (alias or raw form).",
				false);
		b.string("class_fqn",
				"Only used when kind is 'method' or 'field'. Restrict the scan to a single class.",
				false);
		b.bool("include_raw_names",
				"Also try matching `query` against the raw (pre-deobfuscation) name. "
						+ "Useful on obfuscated APKs. Default false.",
				false);
		b.stringArray("access",
				"Access-flag tokens. All required tokens must be set; tokens prefixed with '!' must be "
						+ "absent. Tokens: public, private, protected, static, final, abstract, native, "
						+ "synthetic, volatile, transient, varargs, bridge, interface, enum, annotation. "
						+ "Example: [\"public\", \"!abstract\"].",
				false);
		b.string("annotation",
				"Annotation FQN in dex internal form (L-prefix, slashes, ;-suffix). "
						+ "Example: Landroid/webkit/JavascriptInterface;",
				false);
		b.string("class_kind",
				"Only when kind=class. One of class | interface | enum | annotation.",
				false);
		b.string("superclass",
				"Only when kind=class. Superclass FQN in dot form (e.g. 'android.app.Activity').",
				false);
		b.bool("superclass_transitive",
				"With superclass: include the transitive subclass set (uses ClspGraph). Default false (direct only).",
				false);
		b.string("interface",
				"Only when kind=class. Interface FQN in dot form.",
				false);
		b.bool("interface_transitive",
				"With interface: include transitive implementors. Default false.",
				false);
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String kind = requireString(args, "kind");
		if (!"class".equals(kind) && !"method".equals(kind) && !"field".equals(kind)) {
			throw ToolException.invalidArg("kind", "must be one of class / method / field");
		}
		String query = optString(args, "query");
		boolean regex = optBool(args, "regex", false);
		boolean ignoreCase = optBool(args, "ignore_case", false);
		boolean matchFull = optBool(args, "match_full_name", false);
		int maxResults = Math.max(1, optInt(args, "max_results", 200));
		int skip = Math.max(0, optInt(args, "offset", 0));

		SymbolFilter filter = SymbolFilter.parse(optMap(args, "filter"), kind);
		SearchEngine engine = query != null ? new SearchEngine(query, regex, ignoreCase) : null;
		if (engine == null && filter.isEmpty()) {
			throw ToolException.invalidArg("query",
					"either `query` or at least one `filter` constraint must be provided");
		}

		return session.read(decompiler -> {
			List<JavaClass> scope = resolveScope(decompiler, filter, kind);
			Set<String> superSubtypes = transitiveTypeSet(decompiler, kind, filter.superclass,
					filter.superclassTransitive);
			Set<String> interfaceImpls = transitiveTypeSet(decompiler, kind, filter.iface,
					filter.interfaceTransitive);

			List<Map<String, Object>> hits = new ArrayList<>();
			Pager pager = new Pager(skip, maxResults);

			for (JavaClass cls : scope) {
				if (!filter.matchesPackagePrefix(cls)) {
					continue;
				}
				boolean limitReached = false;
				switch (kind) {
					case "class":
						if (!filter.matchesClassFilter(cls, superSubtypes, interfaceImpls)) {
							continue;
						}
						if (engine != null && !matchesClassName(engine, cls, matchFull, filter.includeRawNames)) {
							continue;
						}
						limitReached = pager.shouldEmit(hits, SymbolFormat.classHit(cls));
						break;
					case "method":
						limitReached = collectMethods(cls, filter, engine, matchFull, hits, pager);
						break;
					case "field":
						limitReached = collectFields(cls, filter, engine, matchFull, hits, pager);
						break;
				}
				if (limitReached) {
					break;
				}
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("kind", kind);
			result.put("hits", hits);
			result.put("count", hits.size());
			result.put("offset", skip);
			result.put("next_offset", pager.hitLimit ? skip + hits.size() : -1);
			return result;
		});
	}

	private List<JavaClass> resolveScope(jadx.api.JadxDecompiler decompiler, SymbolFilter filter, String kind) {
		if (filter.classFqn == null) {
			// Default scope hides aux. The transitive ClspGraph set used for `filter.superclass` /
			// `filter.interface` may still contain aux FQNs internally, but since `cls` here is always
			// drawn from appClasses(), those aux entries can only act as classifiers ("is this app class
			// a subtype of an aux type?") — they can never appear as hits themselves.
			return session.appClasses();
		}
		// kind=class is rejected up front in SymbolFilter.parse, but defensively re-check here.
		if ("class".equals(kind)) {
			throw ToolException.invalidArg("filter.class_fqn",
					"is only meaningful for kind 'method' or 'field'");
		}
		// Explicit FQN lookup: aux is allowed (the user asked for the symbol by name).
		JavaClass single = session.findClass(filter.classFqn);
		if (single == null) {
			throw ToolException.notFound("class", filter.classFqn);
		}
		return List.of(single);
	}

	private static @org.jetbrains.annotations.Nullable Set<String> transitiveTypeSet(
			jadx.api.JadxDecompiler decompiler, String kind, @org.jetbrains.annotations.Nullable String typeName,
			boolean transitive) {
		if (!"class".equals(kind) || typeName == null || !transitive) {
			return null;
		}
		return new HashSet<>(decompiler.getRoot().getClsp().getImplementations(typeName));
	}

	/** Returns true when {@code maxResults} was hit (caller should stop scanning further classes). */
	private static boolean collectMethods(JavaClass cls, SymbolFilter filter,
			@org.jetbrains.annotations.Nullable SearchEngine engine, boolean matchFull,
			List<Map<String, Object>> hits, Pager pager) {
		ClassNode cn = cls.getClassNode();
		List<MethodNode> methods = cn.getMethods();
		if (methods == null) {
			return false;
		}
		for (MethodNode mth : methods) {
			if (!filter.matchesAccessMask(mth.getAccessFlags().rawValue())) {
				continue;
			}
			if (!filter.matchesAnnotation(mth)) {
				continue;
			}
			if (engine != null && !matchesMethodName(engine, mth, matchFull, filter.includeRawNames)) {
				continue;
			}
			if (pager.shouldEmit(hits, SymbolFormat.methodHit(cls, mth))) {
				return true;
			}
		}
		return false;
	}

	/** Returns true when {@code maxResults} was hit. */
	private static boolean collectFields(JavaClass cls, SymbolFilter filter,
			@org.jetbrains.annotations.Nullable SearchEngine engine, boolean matchFull,
			List<Map<String, Object>> hits, Pager pager) {
		ClassNode cn = cls.getClassNode();
		List<FieldNode> fields = cn.getFields();
		if (fields == null) {
			return false;
		}
		for (FieldNode fld : fields) {
			if (!filter.matchesAccessMask(fld.getAccessFlags().rawValue())) {
				continue;
			}
			if (!filter.matchesAnnotation(fld)) {
				continue;
			}
			if (engine != null && !matchesFieldName(engine, cls, fld, matchFull, filter.includeRawNames)) {
				continue;
			}
			if (pager.shouldEmit(hits, SymbolFormat.fieldHit(cls, fld))) {
				return true;
			}
		}
		return false;
	}

	// ---------- name matchers ----------

	private static boolean matchesClassName(SearchEngine eng, JavaClass cls, boolean matchFull, boolean includeRaw) {
		if (eng.find(matchFull ? cls.getFullName() : cls.getName(), 0) >= 0) {
			return true;
		}
		if (includeRaw) {
			String raw = cls.getRawName();
			String rawSimple = simpleName(raw);
			return eng.find(matchFull ? raw : rawSimple, 0) >= 0;
		}
		return false;
	}

	private static boolean matchesMethodName(SearchEngine eng, MethodNode mth, boolean matchFull, boolean includeRaw) {
		MethodInfo info = mth.getMethodInfo();
		if (eng.find(matchFull ? info.getAliasFullName() : info.getAlias(), 0) >= 0) {
			return true;
		}
		if (includeRaw) {
			return eng.find(matchFull ? info.getFullName() : info.getName(), 0) >= 0;
		}
		return false;
	}

	private static boolean matchesFieldName(SearchEngine eng, JavaClass cls, FieldNode fld,
			boolean matchFull, boolean includeRaw) {
		String alias = fld.getAlias();
		if (eng.find(matchFull ? cls.getFullName() + "." + alias : alias, 0) >= 0) {
			return true;
		}
		if (includeRaw) {
			String name = fld.getName();
			return eng.find(matchFull ? cls.getRawName() + "." + name : name, 0) >= 0;
		}
		return false;
	}

	private static String simpleName(String fqn) {
		int dot = fqn.lastIndexOf('.');
		return dot < 0 ? fqn : fqn.substring(dot + 1);
	}

	/**
	 * Skip-then-collect helper for paginated iteration. Centralises the seen / skip / hitLimit
	 * bookkeeping so each {@code collectXxx} loop stays a single readable sweep.
	 */
	private static final class Pager {
		private final int skip;
		private final int maxResults;
		private int seen;
		boolean hitLimit;

		Pager(int skip, int maxResults) {
			this.skip = skip;
			this.maxResults = maxResults;
		}

		/** Adds {@code hit} when past the {@code skip} cursor; returns true after {@code maxResults}. */
		boolean shouldEmit(List<Map<String, Object>> hits, Map<String, Object> hit) {
			seen++;
			if (seen <= skip) {
				return false;
			}
			hits.add(hit);
			if (hits.size() >= maxResults) {
				hitLimit = true;
				return true;
			}
			return false;
		}
	}
}
