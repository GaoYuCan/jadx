package jadx.mcp.tools;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.mcp.JadxSession;
import jadx.mcp.dex.DexXrefs;
import jadx.mcp.util.SchemaBuilder;

/** Searches the DEX instructions for references to an app or external symbol. */
public final class XrefsToTool extends AbstractTool {

	public XrefsToTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "xrefs_to";
	}

	@Override
	public String description() {
		return "Find DEX instructions that reference a class, method, or field. App and external symbols are accepted. "
				+ "Include the JVM descriptor in `target_fqn` for external or overloaded methods. Results include caller methods "
				+ "and bytecode offsets. Field results distinguish reads, writes, and initialization.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("target_fqn", "Class name, method (e.g. 'com.foo.A#bar(I)V'), or field (e.g. 'com.foo.A#value:I').", true)
				.enumString("kind", "Symbol kind to look up.", true, "class", "method", "field")
				.integer("max_results", "Max use-sites to return. Default 500.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String fqn = requireString(args, "target_fqn");
		String kind = requireString(args, "kind");
		int maxResults = Math.max(1, optInt(args, "max_results", 500));
		return session.read(project -> DexXrefs.search(project, fqn, kind, maxResults));
	}
}
