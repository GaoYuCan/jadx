package jadx.mcp.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.mcp.JadxSession;
import jadx.mcp.util.SchemaBuilder;

/**
 * {@code close_file} tool: drop the currently loaded project (decompiler instance, RefTable cache, FQN index).
 * <p>
 * Idempotent: calling it when nothing is loaded simply returns {@code was_loaded=false}. Useful before a long
 * pause to free memory, or as an explicit "I'm done with this APK" signal before {@code open_file}-ing the next.
 */
public final class CloseFileTool extends AbstractTool {

	public CloseFileTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "close_file";
	}

	@Override
	public String description() {
		return "Unload the currently active jadx project and drop all cached state (decompiler, RefTable cache, "
				+ "FQN index). Idempotent — safe to call when nothing is loaded. Subsequent tool calls other than "
				+ "`open_file` will return NOT_LOADED until you reopen a project.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object().build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		JadxSession.CloseSnapshot snap = session.closeAndSnapshot();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("closed", true);
		body.put("was_loaded", snap.wasLoaded);
		if (snap.previousInputFile != null) {
			body.put("previous_input_file", snap.previousInputFile.getAbsolutePath());
		}
		return body;
	}
}
