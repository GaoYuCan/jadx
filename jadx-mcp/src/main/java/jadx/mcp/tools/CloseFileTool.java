package jadx.mcp.tools;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.mcp.JadxSession;
import jadx.mcp.util.SchemaBuilder;

/**
 * {@code close_file} tool: drop the currently loaded project (DEX index and caches).
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
		return "Unload the current APK or DEX and release its index and caches. Safe to call when nothing is loaded.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object().build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		File previous = session.closeAndSnapshot();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("closed", true);
		body.put("was_loaded", previous != null);
		if (previous != null) {
			body.put("previous_input_file", previous.getAbsolutePath());
		}
		return body;
	}
}
