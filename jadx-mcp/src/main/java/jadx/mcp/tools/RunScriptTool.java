package jadx.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.mcp.JadxSession;
import jadx.mcp.script.KotlinScriptRunner;
import jadx.mcp.script.ScriptRunner;
import jadx.mcp.script.ScriptRunner.ScriptRunResult;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/** {@code run_script} tool: execute one Kotlin jadx script against the active project. */
public final class RunScriptTool extends AbstractTool {

	private static final String SCRIPT_SUFFIX = ".jadx.kts";

	private final ScriptRunner runner;

	public RunScriptTool(JadxSession session) {
		this(session, new KotlinScriptRunner());
	}

	RunScriptTool(JadxSession session, ScriptRunner runner) {
		super(session);
		this.runner = runner;
	}

	@Override
	public String name() {
		return "run_script";
	}

	@Override
	public String description() {
		return "Execute a trusted Kotlin jadx script against the currently loaded project. "
				+ "Provide exactly one of `script_path` or `script_text`. Top-level code and "
				+ "`jadx.afterLoad {}` callbacks run immediately against the existing decompiler; the project "
				+ "is not reopened. Registering decompile passes is unsupported. Scripts run as local JVM code "
				+ "with the MCP server's filesystem and process permissions. Returns captured script logs, "
				+ "diagnostics, duration, and success status.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("script_path",
						"Path to a `.jadx.kts` file. Mutually exclusive with `script_text`.", false)
				.string("script_text",
						"Inline `.jadx.kts` source text. Mutually exclusive with `script_path`.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String scriptPathArg = optString(args, "script_path");
		String scriptText = optString(args, "script_text");
		if ((scriptPathArg == null) == (scriptText == null)) {
			throw ToolException.invalidArg(
					"script_path/script_text",
					"provide exactly one non-empty script source");
		}

		boolean inline = scriptText != null;
		Path scriptPath = inline ? writeInlineScript(scriptText) : resolveScriptPath(scriptPathArg);
		try {
			ScriptRunResult result = session.write(decompiler -> runner.run(decompiler, scriptPath));
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("success", result.success);
			body.put("source", inline ? "text" : "path");
			body.put("script_name", result.scriptName);
			if (!inline) {
				body.put("script_path", scriptPath.toString());
			}
			body.put("duration_ms", result.durationMs);
			body.put("after_load_callbacks", result.afterLoadCallbacks);
			body.put("logs", result.logs);
			if (result.error != null) {
				body.put("error", result.error);
			}
			return body;
		} finally {
			if (inline) {
				try {
					Files.deleteIfExists(scriptPath);
				} catch (IOException ignored) {
					scriptPath.toFile().deleteOnExit();
				}
			}
		}
	}

	private static Path resolveScriptPath(String path) {
		Path scriptPath = Path.of(path).toAbsolutePath().normalize();
		if (!Files.exists(scriptPath)) {
			throw ToolException.notFound("script file", scriptPath.toString());
		}
		if (!Files.isRegularFile(scriptPath)) {
			throw ToolException.invalidArg("script_path", "is not a regular file: " + scriptPath);
		}
		if (!scriptPath.getFileName().toString().endsWith(SCRIPT_SUFFIX)) {
			throw ToolException.invalidArg("script_path", "file name must end with `" + SCRIPT_SUFFIX + "`");
		}
		return scriptPath;
	}

	private static Path writeInlineScript(String scriptText) {
		try {
			Path path = Files.createTempFile("jadx-mcp-inline-", SCRIPT_SUFFIX);
			Files.writeString(path, scriptText, StandardCharsets.UTF_8);
			return path.toAbsolutePath().normalize();
		} catch (IOException e) {
			throw new ToolException(
					ToolException.Code.INTERNAL,
					"Failed to create temporary jadx script: " + e.getMessage(),
					null,
					e);
		}
	}
}
