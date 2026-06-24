package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.JavaMethod;
import jadx.mcp.JadxSession;
import jadx.mcp.search.SubtypeOverrideMethods;
import jadx.mcp.util.SchemaBuilder;

/**
 * {@code method_overrides} tool: for an anchor method, list every override-related method whose declaring
 * class is the anchor's class or a subtype of it. This mirrors the GUI's "Define Plus" selection algorithm
 * while keeping the implementation local to jadx-mcp.
 *
 * <p>Direction: <strong>down the type hierarchy</strong>. Given an interface method, you get the concrete
 * implementations; given a base-class method, you get every subclass override. The supertype side of the
 * chain (i.e. methods the anchor itself overrides) is intentionally <em>not</em> included — for that, use
 * {@code inheritance_tree direction=up} on the declaring class and inspect each parent's
 * {@code class_members}.
 */
public final class MethodOverridesTool extends AbstractTool {

	public MethodOverridesTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "method_overrides";
	}

	@Override
	public String description() {
		return "For a method anchor (`target_fqn`, same syntax as `decompile_code` — e.g. 'com.foo.A#bar(I)V'), "
				+ "return every override-related method declared on the anchor's class or any subclass / "
				+ "subinterface. Walks the override chain *downward* only — useful for jumping from an "
				+ "interface method to its concrete implementations or from a base method to all subclass "
				+ "overrides. (For the upward chain — methods the anchor itself overrides — use "
				+ "`inheritance_tree direction=up` on the declaring class.) The anchor itself is included "
				+ "when no related methods exist. This is the GUI's 'Define Plus' feature.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("target_fqn", "Method target, e.g. 'com.foo.A#bar(I)V' (descriptor required to disambiguate overloads).", true)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		Target target = TargetParser.parse(requireString(args, "target_fqn"));
		return session.read(decompiler -> {
			JavaMethod anchor = TargetResolver.resolveMethod(session, target);
			List<JavaMethod> candidates = SubtypeOverrideMethods.collectCandidates(decompiler.getRoot(), anchor);
			List<Map<String, Object>> rows = new ArrayList<>(candidates.size());
			int auxFiltered = 0;
			for (JavaMethod m : candidates) {
				// Drop overrides that live in aux classes — when the anchor is e.g.
				// android.app.Activity#onCreate, the user wants their own overrides, not the dozens
				// of framework subclass overrides bundled with android.jar.
				if (!session.isAppClass(m.getDeclaringClass())) {
					auxFiltered++;
					continue;
				}
				String shortId = m.getMethodNode().getMethodInfo().getShortId();
				String descriptor = shortId.substring(shortId.indexOf('('));
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("class_fqn", m.getDeclaringClass().getFullName());
				row.put("method", m.getName());
				row.put("descriptor", descriptor);
				row.put("target", m.getDeclaringClass().getFullName() + "#" + m.getName() + descriptor);
				rows.add(row);
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("anchor", anchor.getDeclaringClass().getFullName() + "#"
					+ anchor.getMethodNode().getMethodInfo().getShortId());
			body.put("count", rows.size());
			if (auxFiltered > 0) {
				body.put("aux_filtered", auxFiltered);
			}
			body.put("candidates", rows);
			return body;
		});
	}
}
