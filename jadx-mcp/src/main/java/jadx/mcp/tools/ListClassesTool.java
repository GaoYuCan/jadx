package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaPackage;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.mcp.JadxSession;
import jadx.mcp.format.SymbolFormat;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code list_classes} tool: walk the package tree and list classes at the requested depth, plus the
 * immediate sub-packages so the LLM can pick where to drill in next. Acts like {@code ls}: by default
 * shows one level relative to {@code package_prefix} (a directory listing), {@code max_depth=0} flattens
 * the whole subtree (recursive {@code ls -R}).
 *
 * <p>Pure metadata — never triggers decompilation.
 */
public final class ListClassesTool extends AbstractTool {

	public ListClassesTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "list_classes";
	}

	@Override
	public String description() {
		return "List classes under a package, plus its immediate sub-packages so you can navigate further. "
				+ "Behaves like `ls`:\n"
				+ "  - no args: top-level packages (most APKs have no classes at the root).\n"
				+ "  - `package_prefix=com.foo` (default `max_depth=1`): classes directly in `com.foo` plus its\n"
				+ "    direct sub-packages with class counts, so you can decide which sub-tree to descend into.\n"
				+ "  - `package_prefix=com.foo, max_depth=0`: every class under `com.foo` at any depth.\n"
				+ "  - `max_depth=N` (N>=1): classes from depth 0 to N-1 (relative to the prefix).\n"
				+ "Each class row carries a `target` string ready for `decompile_code` / `disassemble`. "
				+ "Pure metadata — no decompilation.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("package_prefix",
						"Package to look into (alias or raw form). Omit for top-level packages.",
						false)
				.integer("max_depth",
						"How deep to descend, relative to `package_prefix` (the prefix itself is depth 0). "
								+ "Default 1 = only classes directly in the prefix (the `ls` view). "
								+ "0 = unlimited (the `ls -R` view).",
						false)
				.bool("include_inner",
						"Include inner classes too. Default false.",
						false)
				.integer("max_results", "Max class rows to return. Default 500.", false)
				.integer("offset", "Pagination cursor over the class list. Default 0.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String pkgPrefix = optString(args, "package_prefix");
		int maxDepth = optInt(args, "max_depth", 1);
		if (maxDepth < 0) {
			throw ToolException.invalidArg("max_depth", "must be >= 0 (0 = unlimited)");
		}
		boolean includeInner = optBool(args, "include_inner", false);
		int maxResults = Math.max(1, optInt(args, "max_results", 500));
		int skip = Math.max(0, optInt(args, "offset", 0));

		return session.read(decompiler -> {
			List<Map<String, Object>> subpackages = collectSubpackages(decompiler, pkgPrefix);

			// Honour the aux-input contract: hide aux classes from "list-shaped" output. Browse tools should
			// only ever surface the app — aux is only reachable by explicit FQN lookup (class_members, etc.).
			List<JavaClass> source = includeInner
					? session.appClasses()
					: session.appClassesNoInner();
			List<Map<String, Object>> hits = new ArrayList<>();
			int seen = 0;
			boolean hasMore = false;
			int matched = 0;

			for (JavaClass cls : source) {
				if (!withinDepth(cls, pkgPrefix, maxDepth)) {
					continue;
				}
				matched++;
				seen++;
				if (seen <= skip) {
					continue;
				}
				if (hits.size() >= maxResults) {
					hasMore = true;
					continue;
				}
				hits.add(classRow(cls));
			}

			Map<String, Object> body = new LinkedHashMap<>();
			body.put("package_prefix", pkgPrefix);
			body.put("max_depth", maxDepth);
			body.put("include_inner", includeInner);
			body.put("subpackages", subpackages);
			body.put("classes", hits);
			body.put("count", hits.size());
			body.put("matched_total", matched);
			body.put("offset", skip);
			body.put("next_offset", hasMore ? skip + hits.size() : -1);
			return body;
		});
	}

	// ---------- depth-aware filtering ----------

	/**
	 * @param maxDepth 0 = unlimited; otherwise N means depths {0..N-1} are allowed (so 1 = direct only).
	 */
	private static boolean withinDepth(JavaClass cls, String pkgPrefix, int maxDepth) {
		String pkg = cls.getPackage();
		String rawPkg = packageOfRaw(cls);
		Integer d = depthRelativeTo(pkg, pkgPrefix);
		if (d == null) {
			d = depthRelativeTo(rawPkg, pkgPrefix);
		}
		if (d == null) return false;
		return maxDepth == 0 || d < maxDepth;
	}

	/**
	 * Depth of {@code pkg} relative to {@code prefix}: 0 if equal, k if {@code pkg = prefix + ".x1.x2…xk"}.
	 * Returns {@code null} when {@code pkg} is not under {@code prefix}.
	 */
	private static Integer depthRelativeTo(String pkg, String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			// "top level" — count dots: "" → 0, "com" → 1, "com.foo" → 2…
			if (pkg.isEmpty()) return 0;
			int dots = 0;
			for (int i = 0; i < pkg.length(); i++) {
				if (pkg.charAt(i) == '.') dots++;
			}
			return dots + 1;
		}
		if (pkg.equals(prefix)) return 0;
		if (!pkg.startsWith(prefix + ".")) return null;
		String tail = pkg.substring(prefix.length() + 1);
		int dots = 0;
		for (int i = 0; i < tail.length(); i++) {
			if (tail.charAt(i) == '.') dots++;
		}
		return dots + 1;
	}

	private static String packageOfRaw(JavaClass cls) {
		String raw = cls.getRawName();
		int dot = raw.lastIndexOf('.');
		return dot < 0 ? "" : raw.substring(0, dot);
	}

	// ---------- sub-package directory listing ----------

	private List<Map<String, Object>> collectSubpackages(JadxDecompiler decompiler, String pkgPrefix) {
		List<JavaPackage> all = decompiler.getPackages();
		List<JavaPackage> direct = new ArrayList<>();
		if (pkgPrefix == null || pkgPrefix.isEmpty()) {
			for (JavaPackage p : all) {
				if (p.getPkgNode().isRoot() && !p.isDefault()) {
					direct.add(p);
				}
			}
		} else {
			JavaPackage parent = findPackage(all, pkgPrefix);
			if (parent != null) {
				direct.addAll(parent.getSubPackages());
			}
			// missing parent isn't fatal here — `list_classes` should still answer (with empty
			// subpackages) when the LLM passes a deobfuscated pkg name that has no nested children.
		}
		List<Map<String, Object>> rows = new ArrayList<>(direct.size());
		for (JavaPackage p : direct) {
			int directApp = directAppClassCount(p);
			int recursiveApp = recursiveAppClassCount(p);
			// Collapse pure-aux subtrees: a sub-package whose entire descent is aux contributes nothing
			// to the app's package tree. Without this we'd surface stub-only directories like
			// `android.view` whenever android.jar is loaded as aux.
			if (recursiveApp == 0) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			String fullName = p.getFullName();
			row.put("fqn", fullName);
			row.put("name", lastSegment(fullName));
			if (!p.getRawFullName().equals(fullName)) {
				row.put("raw_fqn", p.getRawFullName());
			}
			row.put("direct_class_count", directApp);
			row.put("recursive_class_count", recursiveApp);
			row.put("sub_package_count", p.getSubPackages().size());
			rows.add(row);
		}
		return rows;
	}

	private static String lastSegment(String fqn) {
		int dot = fqn.lastIndexOf('.');
		return dot < 0 ? fqn : fqn.substring(dot + 1);
	}

	private static JavaPackage findPackage(List<JavaPackage> all, String fqn) {
		for (JavaPackage p : all) {
			if (p.getFullName().equals(fqn) || p.getRawFullName().equals(fqn)) {
				return p;
			}
		}
		return null;
	}

	private int directAppClassCount(JavaPackage p) {
		int n = 0;
		for (JavaClass cls : p.getClassesNoDup()) {
			if (session.isAppClass(cls)) {
				n++;
			}
		}
		return n;
	}

	private int recursiveAppClassCount(JavaPackage p) {
		int total = directAppClassCount(p);
		for (JavaPackage sub : p.getSubPackages()) {
			total += recursiveAppClassCount(sub);
		}
		return total;
	}

	// ---------- class row formatter ----------

	private static Map<String, Object> classRow(JavaClass cls) {
		ClassNode cn = cls.getClassNode();
		AccessInfo acc = cn.getAccessFlags();
		Map<String, Object> hit = new LinkedHashMap<>();
		String fqn = cls.getFullName();
		hit.put("target", fqn);
		hit.put("fqn", fqn);
		hit.put("name", cls.getName());
		if (!fqn.equals(cls.getRawName())) {
			hit.put("raw_fqn", cls.getRawName());
		}
		hit.put("kind", SymbolFormat.classKind(acc));
		hit.put("access", SymbolFormat.accessTokens(acc, AccessInfo.AFType.CLASS));
		hit.put("is_inner", cn.getClassInfo().isInner());
		ArgType sup = cn.getSuperClass();
		if (sup != null && sup.isObject()) {
			hit.put("super_class", sup.getObject());
		}
		List<String> interfaces = new ArrayList<>();
		for (ArgType i : cn.getInterfaces()) {
			if (i.isObject()) {
				interfaces.add(i.getObject());
			}
		}
		hit.put("interfaces", interfaces);
		String inputFile = cn.getInputFileName();
		if (inputFile != null) {
			hit.put("source_dex", inputFile);
		}
		return hit;
	}
}
