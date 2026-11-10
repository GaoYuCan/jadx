package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.utils.CodeUtils;
import jadx.mcp.JadxSession;
import jadx.mcp.search.SearchEngine;
import jadx.mcp.util.SchemaBuilder;

/** Full-text search across the disassembled DEX classes. */
public final class SearchCodeTool extends AbstractTool {

	public SearchCodeTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "search_code";
	}

	@Override
	public String description() {
		return "Search disassembled Smali with optional regex, case-insensitive matching and package filtering. "
				+ "Hits include line numbers and snippets. Resume with the returned class cursor and offset.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("query", "Substring or regex to search for.", true)
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
						"Resume cursor: skip every class that comes BEFORE this FQN in the "
								+ "class iteration order. Pass the `next_class_fqn` from the previous response.",
						false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String query = requireString(args, "query");
		boolean regex = optBool(args, "regex", false);
		boolean ignoreCase = optBool(args, "ignore_case", false);
		String pkgPrefix = optString(args, "package_prefix");
		int maxResults = Math.max(1, optInt(args, "max_results", 200));
		int skip = Math.max(0, optInt(args, "offset", 0));
		long budgetMs = optInt(args, "time_budget_ms", 30_000);
		String startClassFqn = optString(args, "start_class_fqn");

		SearchEngine engine = new SearchEngine(query, regex, ignoreCase);
		long deadlineNanos = budgetMs > 0 ? System.nanoTime() + budgetMs * 1_000_000L : Long.MAX_VALUE;
		long startNanos = System.nanoTime();
		return session.read(project -> {
			List<Map<String, Object>> hits = new ArrayList<>();
			boolean cursorReached = startClassFqn == null;
			boolean exceededBudget = false;
			String nextClassFqn = null;
			int nextOffset = -1;
			int seen = 0;
			int scanned = 0;
			for (String fqn : project.classes().keySet().stream().sorted().toList()) {
				if (!cursorReached) {
					cursorReached = fqn.equals(startClassFqn);
					if (!cursorReached) {
						continue;
					}
				}
				if (pkgPrefix != null && !fqn.startsWith(pkgPrefix)) {
					continue;
				}
				if (System.nanoTime() >= deadlineNanos) {
					exceededBudget = true;
					nextClassFqn = fqn;
					break;
				}
				scanned++;
				String code = project.find(fqn).data().getDisassembledCode();
				int classSeen = 0;
				int pos = 0;
				while (pos < code.length()) {
					int found = engine.find(code, pos);
					if (found < 0) {
						break;
					}
					seen++;
					classSeen++;
					if (seen > skip) {
						int lineStart = CodeUtils.getLineStartForPos(code, found);
						int lineEnd = CodeUtils.getLineEndForPos(code, found);
						Map<String, Object> row = new LinkedHashMap<>();
						row.put("class_fqn", fqn);
						row.put("line", countLinesUpTo(code, found));
						row.put("snippet", code.substring(lineStart, lineEnd).trim());
						hits.add(row);
						if (hits.size() >= maxResults) {
							nextClassFqn = fqn;
							nextOffset = classSeen;
							break;
						}
					}
					pos = Math.max(found + 1, CodeUtils.getLineEndForPos(code, found) + 1);
				}
				if (hits.size() >= maxResults) {
					break;
				}
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("hits", hits);
			result.put("count", hits.size());
			result.put("offset", skip);
			result.put("next_offset", nextOffset);
			result.put("classes_scanned", scanned);
			result.put("elapsed_ms", (System.nanoTime() - startNanos) / 1_000_000L);
			result.put("exceeded_budget", exceededBudget);
			if (nextClassFqn != null) {
				result.put("next_class_fqn", nextClassFqn);
			}
			return result;
		});
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
