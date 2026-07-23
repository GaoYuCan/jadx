package jadx.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.mcp.JadxSession;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/** {@code current_project} tool: describe the primary input loaded in this MCP session. */
public final class CurrentProjectTool extends AbstractTool {

	public CurrentProjectTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "current_project";
	}

	@Override
	public String description() {
		return "Return the primary input file loaded in the current jadx session and basic file metadata. "
				+ "Safe to call before `open_file`; in that state it returns `loaded=false`.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object().build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		JadxSession.ProjectSnapshot snapshot = session.projectSnapshot();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("loaded", snapshot.loaded);
		if (!snapshot.loaded || snapshot.inputFile == null) {
			return body;
		}

		File input = snapshot.inputFile;
		Path path = input.toPath().toAbsolutePath().normalize();
		body.put("input_file", path.toString());
		body.put("file_name", input.getName());
		body.put("extension", extension(input.getName()));
		try {
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
			body.put("exists", true);
			body.put("size_bytes", attrs.size());
			body.put("last_modified", attrs.lastModifiedTime().toInstant().toString());
		} catch (NoSuchFileException e) {
			body.put("exists", false);
		} catch (IOException e) {
			throw new ToolException(ToolException.Code.INTERNAL,
					"Failed to read input file metadata: " + e.getMessage(), null, e);
		}
		return body;
	}

	private static String extension(String name) {
		int dot = name.lastIndexOf('.');
		if (dot <= 0 || dot == name.length() - 1) {
			return "";
		}
		return name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
