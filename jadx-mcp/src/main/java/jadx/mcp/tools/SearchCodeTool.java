package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.utils.CodeUtils;
import jadx.mcp.JadxSession;
import jadx.mcp.format.RefEntry;
import jadx.mcp.format.RefTable;
import jadx.mcp.search.SearchEngine;
import jadx.mcp.util.SchemaBuilder;

/**
 * {@code search_code} tool: scans the raw decompiled Java source ({@code target=java}) or the disassembled
 * smali ({@code target=smali}) for a pattern, optionally regex / case-insensitive, optionally restricted to a
 * package prefix.
 *
 * <p>Always queries the <b>raw</b> jadx output (no line-number gutter, no inline annotations), so reported
 * line numbers line up with everything else in the API. When a hit's offset falls within a known
 * {@link RefEntry#defPos()} (small distance), the response carries the matching {@code ref_id} so the LLM can
 * deepen with {@code resolve_ref} immediately.
 */
public final class SearchCodeTool extends AbstractTool {

	private static final Logger LOG = LoggerFactory.getLogger(SearchCodeTool.class);

	public SearchCodeTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "search_code";
	}

	@Override
	public String description() {
		return "Full-text search across decompiled Java (`target=java`) or smali (`target=smali`). "
				+ "Supports regex and ignore_case (mirrors the search options in the GUI). Optionally restrict to "
				+ "a `package_prefix`. Returns up to `max_results` hits with raw line numbers and snippets; if a "
				+ "hit happens to land on a known reference, the entry includes a `ref_id` you can pass straight "
				+ "to `resolve_ref`. The query targets the raw decompiled text -- not the line-number gutter or "
				+ "any inline comments added by `decompile_code`."
				+ "\n\nLarge APKs: this tool MUST decompile every class in scope on the first run. Decompiled "
				+ "code is cached for the lifetime of the session (jadx's InMemoryCodeCache), so subsequent "
				+ "`search_code` calls — and any `decompile_code` / `xrefs_to` on the same class — are fast. "
				+ "To stay within the MCP transport timeout, the scan is bounded by `time_budget_ms` (default 30s); "
				+ "when exhausted the response carries `exceeded_budget=true` and `next_class_fqn`, which you pass "
				+ "back in `start_class_fqn` to resume from where it stopped. If you only need to find a symbol "
				+ "by NAME, prefer `search_symbol` — it skips decompilation entirely. If you only need to find a "
				+ "STRING LITERAL (URL, key, error message, SharedPreferences name, crypto algorithm name, ...), "
				+ "prefer `search_strings` — it scans dex `const-string` instructions and encoded values directly "
				+ "and is 1-2 orders of magnitude faster than this tool."
				+ "\n\nPerformance tip for large APKs: prefer `target=smali`. Smali disassembly is roughly an order "
				+ "of magnitude cheaper than Java decompilation and is automatically cached by jadx-core (per "
				+ "`ClassNode.smali`) for the lifetime of the session, so repeated smali scans hit memory after the "
				+ "first pass over each class. Use `target=java` only when you need source-level constructs "
				+ "(lambdas resolved, control flow restructured) — typically after `search_symbol` has narrowed the "
				+ "scope to a handful of classes.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("query", "Substring or regex to search for.", true)
				.enumString("target", "Which view to scan: 'java' (decompiled) or 'smali' (disassembled).", true,
						"java", "smali")
				.bool("regex", "Treat `query` as a Java regex. Default false.", false)
				.bool("ignore_case", "Case-insensitive matching. Default false.", false)
				.string("package_prefix", "Only scan classes whose FQN starts with this prefix.", false)
				.integer("max_results", "Max number of hits to return. Default 200.", false)
				.integer("offset", "Skip this many hits before returning (pagination cursor). Default 0.", false)
				.integer("time_budget_ms",
						"Stop scanning after this many milliseconds and return what we have so far with "
								+ "`exceeded_budget=true` and `next_class_fqn` set. Default 30000. "
								+ "Set <=0 to disable the budget (use with caution on large APKs).",
						false)
				.string("start_class_fqn",
						"Resume cursor: skip every class that comes BEFORE this FQN in the decompiler's "
								+ "class iteration order. Pass the `next_class_fqn` from the previous response.",
						false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String query = requireString(args, "query");
		String target = requireString(args, "target");
		boolean regex = optBool(args, "regex", false);
		boolean ignoreCase = optBool(args, "ignore_case", false);
		String pkgPrefix = optString(args, "package_prefix");
		int maxResults = Math.max(1, optInt(args, "max_results", 200));
		int skip = Math.max(0, optInt(args, "offset", 0));
		long budgetMs = optInt(args, "time_budget_ms", 30_000);
		String startClassFqn = optString(args, "start_class_fqn");
		boolean smali = "smali".equals(target);

		SearchEngine engine = new SearchEngine(query, regex, ignoreCase);
		long deadlineNanos = budgetMs > 0 ? System.nanoTime() + budgetMs * 1_000_000L : Long.MAX_VALUE;
		long startNanos = System.nanoTime();
		return session.read(decompiler -> {
			List<Map<String, Object>> hits = new ArrayList<>();
			int seen = 0;
			boolean hasMore = false;
			boolean exceededBudget = false;
			String nextClassFqn = null;
			boolean cursorReached = startClassFqn == null;
			int classesScanned = 0;
			boolean limitReached = false;

			for (JavaClass cls : session.appClassesNoInner()) {
				if (!cursorReached) {
					if (cls.getFullName().equals(startClassFqn)) {
						cursorReached = true;
					} else {
						continue;
					}
				}
				if (cls.isInner() || cls.isNoCode()) {
					continue;
				}
				if (pkgPrefix != null && !cls.getFullName().startsWith(pkgPrefix)) {
					continue;
				}
				// Budget check: enforced AT class boundaries only, so we always finish the class we started
				// (avoids returning a partial set of hits for a single class).
				if (System.nanoTime() >= deadlineNanos) {
					exceededBudget = true;
					nextClassFqn = cls.getFullName();
					break;
				}
				classesScanned++;
				String code;
				try {
					code = smali ? cls.getSmali() : cls.getCode();
				} catch (Throwable t) {
					LOG.debug("Failed to fetch {} for {}", smali ? "smali" : "code", cls.getFullName(), t);
					continue;
				}
				if (code == null || code.isEmpty()) {
					continue;
				}
				ICodeInfo codeInfo = smali ? null : cls.getCodeInfo();
				RefTable refs = smali ? null : session.refCache().getIfFresh(cls.getFullName(), codeInfo);
				int pos = 0;
				while (pos < code.length()) {
					int found = engine.find(code, pos);
					if (found < 0) {
						break;
					}
					seen++;
					if (seen > skip) {
						int lineStart = CodeUtils.getLineStartForPos(code, found);
						int lineEnd = CodeUtils.getLineEndForPos(code, found);
						String snippet = code.substring(lineStart, lineEnd).trim();
						int line = countLinesUpTo(code, found);
						Map<String, Object> hit = new LinkedHashMap<>();
						hit.put("class_fqn", cls.getFullName());
						hit.put("source", smali ? "smali" : "java");
						hit.put("line", line);
						hit.put("snippet", snippet);
						if (refs != null) {
							RefEntry ref = refs.findNearOffset(found, query.length() + 4);
							if (ref != null) {
								hit.put("ref_id", ref.refId());
								hit.put("target_fqn", ref.targetFqn());
							}
						}
						hits.add(hit);
						if (hits.size() >= maxResults) {
							// peek to see if more would follow
							int next = engine.find(code, lineEnd);
							hasMore = next >= 0;
							if (!hasMore) {
								// Tell caller to start the next page from the class AFTER this one.
								nextClassFqn = nextClassAfter(decompiler, cls);
								if (nextClassFqn != null) {
									hasMore = true;
								}
							} else {
								// Page boundary still inside this class: caller resumes via offset, not a class cursor.
								nextClassFqn = cls.getFullName();
							}
							limitReached = true;
							break;
						}
					}
					pos = Math.max(found + 1, CodeUtils.getLineEndForPos(code, found) + 1);
				}
				if (limitReached) {
					break;
				}
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("hits", hits);
			result.put("count", hits.size());
			result.put("offset", skip);
			result.put("next_offset", hasMore ? skip + hits.size() : -1);
			result.put("classes_scanned", classesScanned);
			result.put("elapsed_ms", (System.nanoTime() - startNanos) / 1_000_000L);
			result.put("exceeded_budget", exceededBudget);
			if (nextClassFqn != null) {
				result.put("next_class_fqn", nextClassFqn);
			}
			return result;
		});
	}

	/** Returns the FQN of the class that comes right after {@code cls} in the iteration order, or null at end. */
	private String nextClassAfter(jadx.api.JadxDecompiler decompiler, JavaClass cls) {
		// Walk the app-only list so the resume cursor never points at an aux class (which the next call's
		// cursor logic would silently skip past, producing confusing "no progress" pages).
		List<JavaClass> all = session.appClassesNoInner();
		boolean past = false;
		for (JavaClass c : all) {
			if (past) {
				return c.getFullName();
			}
			if (c == cls) {
				past = true;
			}
		}
		return null;
	}

	private static int countLinesUpTo(String code, int pos) {
		int n = 1;
		for (int i = 0; i < pos && i < code.length(); i++) {
			if (code.charAt(i) == '\n') {
				n++;
			}
		}
		return n;
	}
}
