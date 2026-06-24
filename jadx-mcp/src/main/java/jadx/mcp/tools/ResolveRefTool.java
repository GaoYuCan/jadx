package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * {@code Rxx} straight out of a {@code decompile_code} response (sidecar or inline) or from a {@code search_code}
 * hit, and gets back the declaring class, method/field signature, override links, and a few use sites.
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
				+ "the def position, a snippet, and lightweight cross-link info. The RefTable is built lazily on "
				+ "first call if the class hasn't been decompiled yet.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("class_fqn", "FQN of the class that contains the reference (the class you decompiled).", true)
				.string("ref_id", "Stable reference id, e.g. 'R42'.", true)
				.integer("max_uses", "Cap on how many use sites to include for the resolved symbol. Default 10.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String classFqn = requireString(args, "class_fqn");
		String refId = requireString(args, "ref_id");
		int maxUses = Math.max(0, optInt(args, "max_uses", 10));

		return session.read(decompiler -> {
			JavaClass javaClass = session.findClass(classFqn);
			if (javaClass == null) {
				throw ToolException.notFound("class", classFqn);
			}
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
				Map<String, Object> resolvedInfo = describeResolved(resolved);
				body.put("resolved", resolvedInfo);
				if (maxUses > 0) {
					// Same contract as xrefs_to: hide aux callers from the preview. Even when the resolved
					// symbol itself lives in aux (e.g. android.app.Activity), the preview answers
					// "who in MY code uses it?".
					List<Map<String, Object>> uses = new ArrayList<>();
					for (JavaNode use : resolved.getUseIn()) {
						if (!session.isAppClass(use)) {
							continue;
						}
						if (uses.size() >= maxUses) {
							break;
						}
						JavaClass enclosing = use.getTopParentClass();
						Map<String, Object> u = new LinkedHashMap<>();
						u.put("in_class", enclosing.getFullName());
						if (use instanceof JavaMethod jm) {
							u.put("in_method", jm.getName());
						}
						u.put("def_pos", use.getDefPos());
						uses.add(u);
					}
					body.put("uses_preview", uses);
				}
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
