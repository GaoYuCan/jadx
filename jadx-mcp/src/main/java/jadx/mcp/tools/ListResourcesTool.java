package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.mcp.JadxSession;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;
import jadx.zip.IZipEntry;

/**
 * {@code list_resources} tool: enumerate the project's resource manifest (zip entries) without
 * decoding any of them. Returns metadata only — no XML decoding, no image probing.
 */
public final class ListResourcesTool extends AbstractTool {

	public ListResourcesTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "list_resources";
	}

	@Override
	public String description() {
		return "List resources in the loaded APK / AAR / ZIP. Pure metadata — no decoding. Filter by `type` "
				+ "(e.g. `lib`, `xml`, `manifest`, `arsc`, `code`), `path_prefix` (matched against the original "
				+ "name, e.g. 'res/layout/'), or `path_glob` (e.g. '**/*.so'). The returned `name` can be "
				+ "fed straight into `decompile_xml` (XML / MANIFEST entries) or `search_resource` (`path_prefix`).";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.enumString("type",
						"Filter by resource type. Omit to list every entry.",
						false,
						"code", "xml", "arsc", "apk", "font", "img", "archive", "videos", "sounds",
						"json", "text", "html", "lib", "manifest", "unknown_bin", "unknown")
				.string("path_prefix", "Only entries whose original name starts with this prefix.", false)
				.string("path_glob",
						"Glob (`*`/`?`/`**`) matched against the original name. Combinable with `path_prefix`.",
						false)
				.integer("max_results", "Max entries to return. Default 500.", false)
				.integer("offset", "Pagination cursor. Default 0.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String typeStr = optString(args, "type");
		ResourceType wantedType = null;
		if (typeStr != null) {
			try {
				wantedType = ResourceType.valueOf(typeStr.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw ToolException.invalidArg("type", "unknown ResourceType '" + typeStr + "'");
			}
		}
		String pathPrefix = optString(args, "path_prefix");
		String pathGlob = optString(args, "path_glob");
		Pattern globPattern = pathGlob != null ? compileGlob(pathGlob) : null;
		int maxResults = Math.max(1, optInt(args, "max_results", 500));
		int skip = Math.max(0, optInt(args, "offset", 0));

		ResourceType filterType = wantedType;

		return session.read(decompiler -> {
			List<ResourceFile> all = decompiler.getResources();
			List<Map<String, Object>> hits = new ArrayList<>();
			int seen = 0;
			boolean hasMore = false;
			int matched = 0;

			for (ResourceFile rf : all) {
				if (filterType != null && rf.getType() != filterType) {
					continue;
				}
				String name = rf.getOriginalName();
				if (pathPrefix != null && !name.startsWith(pathPrefix)) {
					continue;
				}
				if (globPattern != null && !globPattern.matcher(name).matches()) {
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
				hits.add(rowFor(rf));
			}

			Map<String, Object> body = new LinkedHashMap<>();
			body.put("type", typeStr);
			body.put("path_prefix", pathPrefix);
			body.put("path_glob", pathGlob);
			body.put("count", hits.size());
			body.put("matched_total", matched);
			body.put("offset", skip);
			body.put("next_offset", hasMore ? skip + hits.size() : -1);
			body.put("resources", hits);
			return body;
		});
	}

	private static Map<String, Object> rowFor(ResourceFile rf) {
		Map<String, Object> hit = new LinkedHashMap<>();
		String name = rf.getOriginalName();
		hit.put("name", name);
		String deobf = rf.getDeobfName();
		if (deobf != null && !deobf.equals(name)) {
			hit.put("deobf_name", deobf);
		}
		hit.put("type", rf.getType().name());
		hit.put("content_type", rf.getType().getContentType().name());
		IZipEntry zip = rf.getZipEntry();
		if (zip != null) {
			hit.put("zip_entry_name", zip.getName());
			hit.put("size_uncompressed", zip.getUncompressedSize());
			hit.put("size_compressed", zip.getCompressedSize());
		}
		return hit;
	}

	/** Tiny glob -> regex helper supporting * (no /), ? (single char), and ** (any). */
	private static Pattern compileGlob(String glob) {
		StringBuilder sb = new StringBuilder("^");
		int i = 0;
		while (i < glob.length()) {
			char c = glob.charAt(i);
			if (c == '*') {
				if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
					sb.append(".*");
					i += 2;
					continue;
				}
				sb.append("[^/]*");
			} else if (c == '?') {
				sb.append("[^/]");
			} else if ("\\.^$|+(){}[]".indexOf(c) >= 0) {
				sb.append('\\').append(c);
			} else {
				sb.append(c);
			}
			i++;
		}
		sb.append('$');
		try {
			return Pattern.compile(sb.toString());
		} catch (Exception e) {
			throw ToolException.invalidArg("path_glob", "invalid glob: " + e.getMessage());
		}
	}
}
