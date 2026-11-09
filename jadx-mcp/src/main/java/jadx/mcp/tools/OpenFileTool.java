package jadx.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.mcp.JadxMcpArgs;
import jadx.mcp.JadxSession;
import jadx.mcp.project.JadxProjectIO;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code open_file} tool: load (or replace) the project the rest of the tools operate on.
 * <p>
 * A different project replaces the active one, while opening the active input again returns without
 * reloading it. The same MCP server process can therefore hop between APKs / DEXes / JARs without
 * restarting. Holds the session's write lock for the duration of the load decision and load.
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
				+ ".apkm) or a saved `.jadx` project and make it the active project for every other tool. "
				+ "`mode=auto` (default) uses fast for APK/DEX inputs, including compatible `.jadx` projects; "
				+ "other inputs use full. A `.jadx` file restores its input files and user rename data. "
				+ "If a project is already open, "
				+ "a different input replaces it. Opening the active input again returns immediately without "
				+ "reloading; call close_file first when it must be reopened with different options. "
				+ "If env JADX_MCP_AUX_INPUTS is set, the listed jars are loaded in full mode; fast mode "
				+ "reports that they are unavailable. Returns a small status payload with the "
				+ "input path, indexed class count (split into app + aux), and any errors / warnings reported by "
				+ "jadx during loading.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("path", "Absolute (recommended) or working-directory-relative path to the input file.", true)
				.enumString("mode", "auto (default): fast for APK/DEX, full otherwise; or force fast/full.",
						false, "auto", "fast", "full")
				.bool("skip_resources", "Skip decoding resources; faster on resource-heavy APKs. Default false.", false)
				.integer("threads_count", "Decompilation thread count. 0 (default) means use all CPU cores.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String path = requireString(args, "path");
		String requestedMode = optString(args, "mode", "auto");
		if (!requestedMode.equals("auto") && !requestedMode.equals("fast") && !requestedMode.equals("full")) {
			throw ToolException.invalidArg("mode", "must be auto, fast or full");
		}
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
		JadxProjectIO.LoadedProject savedProject = null;
		List<File> projectInputs = List.of(file);
		if (file.getName().toLowerCase(Locale.ROOT).endsWith(".jadx")) {
			try {
				savedProject = JadxProjectIO.read(file.toPath());
			} catch (IOException e) {
				throw ToolException.invalidArg("path", "failed to read jadx project: " + e.getMessage());
			}
			projectInputs = new ArrayList<>(savedProject.inputFiles().size());
			for (Path inputPath : savedProject.inputFiles()) {
				File projectInput = inputPath.toFile();
				if (!projectInput.exists()) {
					throw ToolException.notFound("project input file", inputPath.toString());
				}
				if (!projectInput.isFile()) {
					throw ToolException.invalidArg("path", "project input is not a regular file: " + inputPath);
				}
				projectInputs.add(projectInput);
			}
		}

		File previous = session.getInputFile();
		boolean supportedFast = projectInputs.stream().allMatch(OpenFileTool::supportsFast);
		if (requestedMode.equals("fast") && !supportedFast) {
			throw new ToolException(ToolException.Code.UNSUPPORTED,
					"Fast mode supports APK/DEX project inputs only. Use mode=full.");
		}
		boolean fast = !requestedMode.equals("full") && supportedFast;
		JadxSession.LoadResult loadResult;
		try {
			if (fast) {
				loadResult = session.loadFast(file, projectInputs,
						savedProject == null ? new jadx.api.data.impl.JadxCodeData() : savedProject.codeData());
			} else if (savedProject == null) {
				loadResult = session.load(file, auxInputs, skipResources, threadsCount);
			} else {
				loadResult = session.loadProject(file, projectInputs, savedProject.codeData(),
						auxInputs, skipResources, threadsCount);
			}
		} catch (Throwable t) {
			LOG.error("Failed to load {}", file, t);
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("path", file.getAbsolutePath());
			details.put("cause", t.getClass().getSimpleName() + ": " + t.getMessage());
			throw new ToolException(ToolException.Code.INTERNAL,
					"Failed to load '" + file + "': " + t.getMessage(), details, t);
		}
		if (loadResult == JadxSession.LoadResult.ALREADY_LOADED) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("loaded", true);
			body.put("already_loaded", true);
			body.put("input_file", file.getAbsolutePath());
			body.put("mode", session.mode());
			body.put("message", "项目已经加载。如果确定当前加载配置不适用于你的任务，请先调用 close_file，再重新调用 open_file。");
			return body;
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("loaded", true);
		body.put("input_file", file.getAbsolutePath());
		body.put("mode", session.mode());
		if (savedProject != null) {
			List<String> inputPaths = new ArrayList<>(projectInputs.size());
			for (File projectInput : projectInputs) {
				inputPaths.add(projectInput.getAbsolutePath());
			}
			body.put("project_input_files", inputPaths);
			body.put("rename_count", savedProject.codeData().getRenames().size());
		}
		if (previous != null && !previous.equals(file)) {
			body.put("previous_input_file", previous.getAbsolutePath());
		}
		if (!auxInputs.isEmpty()) {
			List<String> auxPaths = new ArrayList<>(auxInputs.size());
			for (File aux : auxInputs) {
				auxPaths.add(aux.getAbsolutePath());
			}
			body.put("aux_input_files", auxPaths);
			if (fast) {
				body.put("warning", "Fast mode does not index auxiliary inputs; reopen with mode=full to access them.");
			}
		}
		// Read after-load stats inside the read lock to be consistent with concurrent tool calls.
		Map<String, Object> stats = fast ? session.fastRead(project -> {
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("class_count", project.classCount());
			s.put("app_class_count", project.classCount());
			s.put("aux_class_count", 0);
			s.put("errors", 0);
			s.put("warnings", 0);
			return s;
		}) : session.read(decompiler -> {
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

	private static boolean supportsFast(File file) {
		String name = file.getName().toLowerCase(Locale.ROOT);
		return name.endsWith(".apk") || name.endsWith(".dex");
	}
}
