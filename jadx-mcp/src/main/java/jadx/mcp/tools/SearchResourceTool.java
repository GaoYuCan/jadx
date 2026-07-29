package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.ICodeInfo;
import jadx.api.ResourceFile;
import jadx.api.resources.ResourceContentType;
import jadx.api.utils.CodeUtils;
import jadx.core.xmlgen.ResContainer;
import jadx.mcp.JadxSession;
import jadx.mcp.search.SearchEngine;
import jadx.mcp.util.SchemaBuilder;

/**
 * {@code search_resource} tool: scans decoded XML / text resources for a pattern, with the same regex /
 * ignore-case knobs as {@code search_code}, plus path-based filtering and a max-size cap to avoid OOMs on
 * outsized assets.
 */
public final class SearchResourceTool extends AbstractTool {

	private static final Logger LOG = LoggerFactory.getLogger(SearchResourceTool.class);

	public SearchResourceTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "search_resource";
	}

	@Override
	public String description() {
		return "Full-text search across decoded text resources (AndroidManifest.xml, res/**.xml, ARSC, etc.). "
				+ "Same regex / ignore_case options as `search_code`. Optionally restrict by `path_prefix` "
				+ "(string prefix on resource path) and/or `path_glob` (e.g. 'res/values/*.xml'). Resources "
				+ "larger than `max_size_kb` are skipped to avoid loading binary blobs into memory. Hits include "
				+ "the resource path and the matched line.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("query", "Substring or regex to search for.", true)
				.bool("regex", "Treat `query` as a Java regex. Default false.", false)
				.bool("ignore_case", "Case-insensitive matching. Default false.", false)
				.string("path_prefix", "Only scan resources whose path starts with this prefix.", false)
				.string("path_glob", "Glob pattern (e.g. 'res/values/*.xml') applied to the resource path.", false)
				.integer("max_size_kb", "Skip resources larger than this many KB. Default 1024.", false)
				.integer("max_results", "Max number of hits to return. Default 200.", false)
				.integer("offset", "Skip this many hits before returning (pagination cursor). Default 0.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String query = requireString(args, "query");
		boolean regex = optBool(args, "regex", false);
		boolean ignoreCase = optBool(args, "ignore_case", false);
		String pathPrefix = optString(args, "path_prefix");
		String pathGlob = optString(args, "path_glob");
		int maxSizeKb = Math.max(1, optInt(args, "max_size_kb", 1024));
		int maxResults = Math.max(1, optInt(args, "max_results", 200));
		int skip = Math.max(0, optInt(args, "offset", 0));

		Pattern globPattern = pathGlob == null ? null : globToRegex(pathGlob);
		SearchEngine engine = new SearchEngine(query, regex, ignoreCase);
		long maxBytes = (long) maxSizeKb * 1024L;

		return session.read(decompiler -> {
			List<Map<String, Object>> hits = new ArrayList<>();
			int seen = 0;
			boolean hasMore = false;
			boolean limitReached = false;
			for (ResourceFile rf : decompiler.getResources()) {
				if (rf.getType().getContentType() != ResourceContentType.CONTENT_TEXT) {
					continue;
				}
				String path = rf.getDeobfName();
				if (pathPrefix != null && !path.startsWith(pathPrefix)) {
					continue;
				}
				if (globPattern != null && !globPattern.matcher(path).matches()) {
					continue;
				}
				ResContainer container;
				try {
					container = rf.loadContent();
				} catch (Throwable t) {
					LOG.debug("Failed to load resource {}", path, t);
					continue;
				}
				if (container == null) {
					continue;
				}
				if (container.getDataType() != ResContainer.DataType.TEXT
						&& container.getDataType() != ResContainer.DataType.RES_TABLE) {
					continue;
				}
				ICodeInfo text = container.getText();
				if (text == null) {
					continue;
				}
				String code = text.getCodeStr();
				if (code == null || code.isEmpty()) {
					continue;
				}
				if ((long) code.length() > maxBytes) {
					continue;
				}
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
						int line = 1;
						for (int i = 0; i < found; i++) {
							if (code.charAt(i) == '\n') {
								line++;
							}
						}
						Map<String, Object> hit = new LinkedHashMap<>();
						hit.put("resource_path", path);
						hit.put("resource_type", rf.getType().name());
						hit.put("line", line);
						hit.put("snippet", snippet);
						hits.add(hit);
						if (hits.size() >= maxResults) {
							int next = engine.find(code, lineEnd);
							hasMore = next >= 0;
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
			return result;
		});
	}

	/** Translate a simple POSIX-style glob into a regex. Supports {@code *}, {@code ?}, and {@code **}. */
	private static Pattern globToRegex(String glob) {
		StringBuilder re = new StringBuilder("^");
		int i = 0;
		while (i < glob.length()) {
			char c = glob.charAt(i);
			switch (c) {
				case '*':
					if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
						re.append(".*");
						i += 2;
						continue;
					}
					re.append("[^/]*");
					break;
				case '?':
					re.append("[^/]");
					break;
				case '.':
				case '(':
				case ')':
				case '+':
				case '|':
				case '^':
				case '$':
				case '@':
				case '%':
				case '{':
				case '}':
				case '[':
				case ']':
				case '\\':
					re.append('\\').append(c);
					break;
				default:
					re.append(c);
			}
			i++;
		}
		re.append('$');
		return Pattern.compile(re.toString());
	}
}
