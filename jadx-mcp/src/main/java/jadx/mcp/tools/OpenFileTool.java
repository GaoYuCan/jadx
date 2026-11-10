package jadx.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.mcp.JadxSession;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/** `open_file` indexes an APK or DEX and makes it the active input. */
public final class OpenFileTool extends AbstractTool {

	public OpenFileTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "open_file";
	}

	@Override
	public String description() {
		return "Open an APK or DEX. A different input replaces the active project. Opening the same file "
				+ "returns without reloading it. Call `close_file` first to reload an input changed on disk.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("path", "Absolute or working-directory-relative path to an APK or DEX.", true)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String path = requireString(args, "path");
		File file = new File(path);
		if (!file.exists()) {
			throw ToolException.notFound("input file", path);
		}
		if (!file.isFile()) {
			throw ToolException.invalidArg("path", "is not a regular file: " + path);
		}

		JadxSession.OpenResult opened;
		try {
			opened = session.load(file);
		} catch (IOException e) {
			throw new ToolException(ToolException.Code.INTERNAL,
					"Failed to open '" + path + "': " + e.getMessage(), null, e);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("loaded", true);
		body.put("input_file", file.getAbsolutePath());
		body.put("class_count", opened.classCount());
		if (opened.alreadyLoaded()) {
			body.put("already_loaded", true);
			body.put("message", "项目已经加载。如需重新加载，请先调用 close_file。");
		} else if (opened.previousInputFile() != null) {
			body.put("previous_input_file", opened.previousInputFile().getAbsolutePath());
		}
		return body;
	}
}
