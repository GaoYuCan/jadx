package jadx.mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.mcp.JadxMcpArgs;
import jadx.mcp.JadxSession;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code open_file} tool: load (or replace) the project the rest of the tools operate on.
 * <p>
 * If a project is already loaded, it is closed first; the same MCP server process can therefore hop between
 * APKs / DEXes / JARs without restarting. Holds the session's write lock for the duration of the load.
 */
public final class OpenFileTool extends AbstractTool {

	private static final Logger LOG = LoggerFactory.getLogger(OpenFileTool.class);

	public OpenFileTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "open_file";
	}

	@Override
	public String description() {
		return "Load a jadx-decodable input (.apk, .dex, .jar, .class, .smali, .zip, .aar, .arsc, .aab, .xapk, "
				+ ".apkm) and make it the active project for every other tool. If a project is already open, "
				+ "it is closed first and its caches are dropped, so this tool also serves as 'switch project'. "
				+ "If env JADX_MCP_AUX_INPUTS is set, the listed jars (typically android.jar) are loaded "
				+ "alongside the primary input as auxiliary inputs — they participate in symbol resolution and "
				+ "xref but are hidden from list_classes / search_*. Returns a small status payload with the "
				+ "input path, indexed class count (split into app + aux), and any errors / warnings reported by "
				+ "jadx during loading.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("path", "Absolute (recommended) or working-directory-relative path to the input file.", true)
				.bool("skip_resources", "Skip decoding resources; faster on resource-heavy APKs. Default false.", false)
				.integer("threads_count", "Decompilation thread count. 0 (default) means use all CPU cores.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String path = requireString(args, "path");
		boolean skipResources = optBool(args, "skip_resources", false);
		int threadsCount = Math.max(0, optInt(args, "threads_count", 0));

		File file = new File(path);
		if (!file.exists()) {
			throw ToolException.notFound("input file", path);
		}
		if (!file.isFile()) {
			throw ToolException.invalidArg("path", "is not a regular file: " + path);
		}

		// Aux inputs are resolved every time from the env variable (re-read, not cached) so users can
		// `export JADX_MCP_AUX_INPUTS=...` in the parent shell, edit it, and have the next open_file
		// pick up the new value without restarting the server.
		List<File> auxInputs = JadxMcpArgs.resolveEnvAuxInputs();

		File previous = session.getInputFile();
		try {
			session.load(file, auxInputs, skipResources, threadsCount);
		} catch (Throwable t) {
			LOG.error("Failed to load {}", file, t);
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("path", file.getAbsolutePath());
			details.put("cause", t.getClass().getSimpleName() + ": " + t.getMessage());
			throw new ToolException(ToolException.Code.INTERNAL,
					"Failed to load '" + file + "': " + t.getMessage(), details, t);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("loaded", true);
		body.put("input_file", file.getAbsolutePath());
		if (previous != null && !previous.equals(file)) {
			body.put("previous_input_file", previous.getAbsolutePath());
		}
		if (!auxInputs.isEmpty()) {
			List<String> auxPaths = new ArrayList<>(auxInputs.size());
			for (File aux : auxInputs) {
				auxPaths.add(aux.getAbsolutePath());
			}
			body.put("aux_input_files", auxPaths);
		}
		// Read after-load stats inside the read lock to be consistent with concurrent tool calls.
		Map<String, Object> stats = session.read(decompiler -> {
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("class_count", session.loadedClassCount());
			s.put("app_class_count", session.loadedAppClassCount());
			s.put("aux_class_count", session.loadedAuxClassCount());
			s.put("errors", decompiler.getErrorsCount());
			s.put("warnings", decompiler.getWarnsCount());
			return s;
		});
		body.putAll(stats);
		return body;
	}
}
