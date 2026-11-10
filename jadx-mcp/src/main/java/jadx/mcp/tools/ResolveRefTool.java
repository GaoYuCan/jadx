package jadx.mcp.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.mcp.JadxSession;
import jadx.mcp.format.RefEntry;
import jadx.mcp.format.RefTable;
import jadx.mcp.format.RefTableBuilder;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code resolve_ref} tool: turn a stable {@code (class_fqn, ref_id)} pair into a fully resolved symbol record.
 * <p>
 * This is the primary "what is that symbol?" path. The LLM never has to count lines/columns; it copies
 * {@code Rxx} straight out of a {@code decompile_code} response (sidecar or inline) or the source sidecar and gets the target's class and member signature.
 */
public final class ResolveRefTool extends AbstractTool {

	public ResolveRefTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "resolve_ref";
	}

	@Override
	public String description() {
		return "Look up a reference produced by `decompile_code` (sidecar `refs` or inline `/*->...#Rxx*/` markers). "
				+ "`class_fqn` is the class the reference appears in (the same FQN you passed to `decompile_code`); "
				+ "`ref_id` is the literal `Rxx` string. Returns the resolved symbol's declaring class and member, "
				+ "its position and a snippet. Use `xrefs_to` for project-wide bytecode references.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("class_fqn", "FQN of the class that contains the reference (the class you decompiled).", true)
				.string("ref_id", "Stable reference id, e.g. 'R42'.", true)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String classFqn = requireString(args, "class_fqn");
		String refId = requireString(args, "ref_id");

		return session.withDecompiler(project -> Set.of(classFqn), decompiler -> {
			JavaClass javaClass = TargetResolver.resolveClass(decompiler, classFqn);
			ICodeInfo codeInfo = javaClass.getCodeInfo();
			RefTable table = session.refCache().getIfFresh(classFqn, codeInfo);
			if (table == null) {
				table = RefTableBuilder.build(decompiler, javaClass);
				session.refCache().put(classFqn, codeInfo, table);
			}
			RefEntry entry = table.get(refId);
			if (entry == null) {
				throw ToolException.notFound("ref_id", refId + " in " + classFqn);
			}
			JavaNode resolved = decompiler.getJavaNodeAtPosition(codeInfo, entry.defPos());
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("class_fqn", classFqn);
			body.put("ref_id", entry.refId());
			body.put("line", entry.line());
			body.put("col", entry.col());
			body.put("def_pos", entry.defPos());
			body.put("kind", entry.kind().name());
			body.put("snippet", entry.snippet());
			body.put("target_fqn", entry.targetFqn());
			if (entry.targetMember() != null) {
				body.put("target_member", entry.targetMember());
			}
			if (entry.targetDescriptor() != null) {
				body.put("target_descriptor", entry.targetDescriptor());
			}
			if (resolved != null) {
				body.put("resolved", describeResolved(resolved));
			}
			return body;
		});
	}

	private static Map<String, Object> describeResolved(JavaNode node) {
		Map<String, Object> m = new LinkedHashMap<>();
		if (node instanceof JavaClass jc) {
			m.put("type", "CLASS");
			m.put("fqn", jc.getFullName());
			m.put("def_pos", jc.getDefPos());
		} else if (node instanceof JavaMethod jm) {
			m.put("type", "METHOD");
			m.put("class_fqn", jm.getDeclaringClass().getFullName());
			m.put("name", jm.getName());
			String shortId = jm.getMethodNode().getMethodInfo().getShortId();
			m.put("descriptor", shortId.substring(shortId.indexOf('(')));
			m.put("def_pos", jm.getDefPos());
		} else if (node instanceof JavaField jf) {
			m.put("type", "FIELD");
			m.put("class_fqn", jf.getDeclaringClass().getFullName());
			m.put("name", jf.getName());
			m.put("def_pos", jf.getDefPos());
		} else {
			m.put("type", "OTHER");
			m.put("fqn", node.getFullName());
		}
		return m;
	}
}
