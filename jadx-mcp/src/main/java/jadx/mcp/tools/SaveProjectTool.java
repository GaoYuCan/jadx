package jadx.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.mcp.JadxSession;
import jadx.mcp.project.JadxProjectIO;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code save_project} tool: persist the current input and user renames as a jadx-gui project file.
 */
public final class SaveProjectTool extends AbstractTool {

	public SaveProjectTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "save_project";
	}

	@Override
	public String description() {
		return "Save the current primary input and all MCP rename records as a jadx-gui compatible v2 `.jadx` "
				+ "project. Appends `.jadx` when omitted. Existing files are rejected unless `overwrite=true`.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("output_path", "Destination project file. `.jadx` is appended when missing.", true)
				.bool("overwrite", "Replace an existing project file. Default false.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		Path output = withProjectExtension(Path.of(requireString(args, "output_path"))).toAbsolutePath().normalize();
		boolean overwrite = optBool(args, "overwrite", false);
		Path parent = output.getParent();
		if (parent == null || !Files.isDirectory(parent)) {
			throw ToolException.invalidArg("output_path", "parent directory does not exist: " + parent);
		}
		if (Files.isDirectory(output)) {
			throw ToolException.invalidArg("output_path", "destination is a directory: " + output);
		}
		if (!overwrite && Files.exists(output)) {
			throw ToolException.invalidArg("output_path",
					"file already exists; pass overwrite=true to replace it: " + output);
		}

		JadxSession.ProjectSnapshot snapshot = session.projectSnapshot();
		if (!snapshot.loaded || snapshot.inputFile == null) {
			throw new ToolException(ToolException.Code.NOT_LOADED,
					"No jadx project is currently loaded. Call `open_file` first.");
		}
		File input = snapshot.inputFile;
		try {
			List<Path> projectInputs = snapshot.projectInputFiles.stream()
					.map(File::toPath)
					.toList();
			JadxProjectIO.write(output, projectInputs, snapshot.codeData, overwrite);
		} catch (FileAlreadyExistsException e) {
			throw ToolException.invalidArg("output_path",
					"file already exists; pass overwrite=true to replace it: " + output);
		} catch (IOException e) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("output_path", output.toString());
			throw new ToolException(ToolException.Code.INTERNAL,
					"Failed to save jadx project: " + e.getMessage(), details, e);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("saved", true);
		body.put("output_file", output.toString());
		body.put("input_file", input.getAbsolutePath());
		body.put("rename_count", snapshot.renames.size());
		return body;
	}

	private static Path withProjectExtension(Path path) {
		Path fileName = path.getFileName();
		if (fileName != null && !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".jadx")) {
			return path.resolveSibling(fileName + ".jadx");
		}
		return path;
	}
}
