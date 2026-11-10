package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.core.dex.info.AccessInfo;
import jadx.mcp.JadxSession;
import jadx.mcp.dex.DexProject;
import jadx.mcp.dex.DexProject.ClassEntry;
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
						"Package to look into. Omit for top-level packages.",
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
		return session.read(project -> list(project, pkgPrefix, maxDepth, includeInner, maxResults, skip));
	}

	private static Map<String, Object> list(DexProject project, String prefix, int maxDepth,
			boolean includeInner, int maxResults, int skip) {
		List<ClassEntry> source = project.classes().values().stream()
				.sorted(Comparator.comparing(ClassEntry::fqn)).toList();
		List<Map<String, Object>> hits = new ArrayList<>();
		Map<String, int[]> childCounts = new java.util.TreeMap<>();
		Map<String, Set<String>> grandchildren = new java.util.HashMap<>();
		int matched = 0;
		for (ClassEntry entry : source) {
			String fqn = entry.fqn();
			String pkg = fqn.substring(0, Math.max(0, fqn.lastIndexOf('.')));
			boolean inner = lastSegment(fqn).contains("$");
			String child = immediateChild(pkg, prefix);
			if (child != null) {
				int[] counts = childCounts.computeIfAbsent(child, ignored -> new int[2]);
				counts[1]++;
				if (pkg.equals(child)) {
					counts[0]++;
				}
				String below = immediateChild(pkg, child);
				if (below != null) {
					grandchildren.computeIfAbsent(child, ignored -> new TreeSet<>()).add(below);
				}
			}
			if (!includeInner && inner) {
				continue;
			}
			Integer depth = depthRelativeTo(pkg, prefix);
			if (depth == null || maxDepth != 0 && depth >= maxDepth) {
				continue;
			}
			if (matched++ < skip || hits.size() >= maxResults) {
				continue;
			}
			AccessInfo access = new AccessInfo(entry.access(), AccessInfo.AFType.CLASS);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("target", fqn);
			row.put("fqn", fqn);
			row.put("name", lastSegment(fqn));
			row.put("kind", SymbolFormat.classKind(access));
			row.put("access", SymbolFormat.accessTokens(access, AccessInfo.AFType.CLASS));
			row.put("is_inner", inner);
			if (entry.superClass() != null) {
				row.put("super_class", entry.superClass());
			}
			row.put("interfaces", entry.interfaces());
			row.put("source_dex", entry.reader().getInputFileName());
			hits.add(row);
		}
		List<Map<String, Object>> subpackages = new ArrayList<>();
		for (Map.Entry<String, int[]> child : childCounts.entrySet()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("fqn", child.getKey());
			row.put("name", lastSegment(child.getKey()));
			row.put("direct_class_count", child.getValue()[0]);
			row.put("recursive_class_count", child.getValue()[1]);
			row.put("sub_package_count", grandchildren.getOrDefault(child.getKey(), Set.of()).size());
			subpackages.add(row);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("package_prefix", prefix);
		body.put("max_depth", maxDepth);
		body.put("include_inner", includeInner);
		body.put("subpackages", subpackages);
		body.put("classes", hits);
		body.put("count", hits.size());
		body.put("matched_total", matched);
		body.put("offset", skip);
		body.put("next_offset", matched > skip + hits.size() ? skip + hits.size() : -1);
		return body;
	}

	private static String immediateChild(String pkg, String prefix) {
		String base = prefix == null || prefix.isEmpty() ? "" : prefix + '.';
		if (!pkg.startsWith(base) || pkg.length() <= base.length()) {
			return null;
		}
		String tail = pkg.substring(base.length());
		int dot = tail.indexOf('.');
		return base + (dot < 0 ? tail : tail.substring(0, dot));
	}

	// ---------- depth-aware filtering ----------

	/**
	 * Depth of {@code pkg} relative to {@code prefix}: 0 if equal, k if {@code pkg = prefix + ".x1.x2…xk"}.
	 * Returns {@code null} when {@code pkg} is not under {@code prefix}.
	 */
	private static Integer depthRelativeTo(String pkg, String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			// "top level" — count dots: "" → 0, "com" → 1, "com.foo" → 2…
			if (pkg.isEmpty()) {
				return 0;
			}
			int dots = 0;
			for (int i = 0; i < pkg.length(); i++) {
				if (pkg.charAt(i) == '.') {
					dots++;
				}
			}
			return dots + 1;
		}
		if (pkg.equals(prefix)) {
			return 0;
		}
		if (!pkg.startsWith(prefix + ".")) {
			return null;
		}
		String tail = pkg.substring(prefix.length() + 1);
		int dots = 0;
		for (int i = 0; i < tail.length(); i++) {
			if (tail.charAt(i) == '.') {
				dots++;
			}
		}
		return dots + 1;
	}

	private static String lastSegment(String fqn) {
		int dot = fqn.lastIndexOf('.');
		return dot < 0 ? fqn : fqn.substring(dot + 1);
	}
}
