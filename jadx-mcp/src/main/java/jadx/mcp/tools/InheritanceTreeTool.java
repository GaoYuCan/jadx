package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.core.clsp.ClspClass;
import jadx.core.clsp.ClspGraph;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.mcp.JadxSession;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code inheritance_tree} tool: generic up / down type-hierarchy view.
 *
 * <p>Up walks the {@code superClass} chain and accumulates interfaces (direct + inherited) in O(depth).
 * Only meaningful for classes loaded in the project (an {@code <init>}-time {@code ClassNode}); for
 * pure framework classes (e.g. {@code java.lang.Object}, {@code android.app.Activity}) we don't have a
 * ClassNode and reject {@code direction=up} with a precise error.
 *
 * <p>Down classification leans on the JLS:
 * <ul>
 *   <li>If the target is an <strong>interface</strong>, every transitive subtype is reported as an
 *       <code>implementor</code> (sub-interfaces and concrete implementors alike — both pass an
 *       {@code instanceof} check, which is what users actually care about).</li>
 *   <li>If the target is a <strong>class</strong>, every transitive subtype is reported as a
 *       <code>subclass</code> (interfaces cannot extend classes, so the dichotomy is total).</li>
 * </ul>
 * "Direct" mode (default) walks the loaded app classes and matches against declared
 * {@code superClass}/{@code interfaces}; "transitive" mode uses
 * {@link ClspGraph#getImplementations(String)} which returns the precomputed closed set in O(1).
 */
public final class InheritanceTreeTool extends AbstractTool {

	public InheritanceTreeTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "inheritance_tree";
	}

	@Override
	public String description() {
		return "Walk the type hierarchy of a class. `direction=up` returns the super-chain + interfaces "
				+ "(direct and inherited). `direction=down` returns subclasses (when target is a class) or "
				+ "implementors (when target is an interface) — direct only by default; set "
				+ "`transitive=true` for the full closed set via ClspGraph. `direction=both` (default) "
				+ "does both. Each entry has `source=app` (class loaded in this project) or `source=clsp` "
				+ "(framework / runtime classpath only). Heads-up: `transitive=true` on a deeply-rooted "
				+ "target like `java.lang.Object` returns tens of thousands of entries — start with "
				+ "`transitive=false` to gauge size. `direction=up` is rejected for framework classes "
				+ "since we have no ClassNode for them.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("class_fqn", "Fully qualified class name (alias or raw form). Required.", true)
				.enumString("direction", "Which direction to walk. Default both.",
						false, "up", "down", "both")
				.bool("transitive",
						"Down direction only: include the transitive closure of subclasses / implementors. "
								+ "Default false (direct children only).",
						false)
				.integer("max_results", "Max subclass / implementor entries to return. Default 500.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String classFqn = requireString(args, "class_fqn");
		String direction = optString(args, "direction", "both");
		if (!direction.equals("up") && !direction.equals("down") && !direction.equals("both")) {
			throw ToolException.invalidArg("direction", "must be one of up | down | both");
		}
		boolean transitive = optBool(args, "transitive", false);
		int maxResults = Math.max(1, optInt(args, "max_results", 500));

		return session.read(decompiler -> {
			JavaClass cls = session.findClass(classFqn);
			ClassNode cn = cls != null ? cls.getClassNode() : null;
			String targetRaw = cn != null ? cn.getClassInfo().getType().getObject() : classFqn;
			boolean appClass = cls != null;
			if (!appClass && (direction.equals("up") || direction.equals("both"))) {
				// `up` requires walking ClassNode super chain — only meaningful for app classes.
				// For pure clsp targets (java.lang.Object, android.app.Activity, ...) only `down` works.
				if (direction.equals("up")) {
					throw ToolException.notFound("class", classFqn
							+ " (not loaded in this project; only `direction=down` works for framework classes)");
				}
			}

			Map<String, Object> body = new LinkedHashMap<>();
			if (cls != null) {
				body.put("fqn", cls.getFullName());
				if (!cls.getFullName().equals(cls.getRawName())) {
					body.put("raw_fqn", cls.getRawName());
				}
			} else {
				body.put("fqn", classFqn);
			}
			body.put("source", appClass ? "app" : "clsp");
			body.put("direction", direction);
			body.put("transitive", transitive);

			boolean wantUp = appClass && cn != null && (direction.equals("up") || direction.equals("both"));
			boolean wantDown = direction.equals("down") || direction.equals("both");

			if (wantUp && cn != null) {
				List<Map<String, Object>> superChain = new ArrayList<>();
				Set<String> directIfs = new LinkedHashSet<>();
				Set<String> inheritedIfs = new LinkedHashSet<>();

				for (ArgType i : cn.getInterfaces()) {
					if (i.isObject()) {
						directIfs.add(i.getObject());
					}
				}

				ArgType sup = cn.getSuperClass();
				int safety = 64;
				while (sup != null && sup.isObject() && safety-- > 0) {
					String name = sup.getObject();
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("fqn", name);
					JavaClass parentCls = session.findClass(name);
					entry.put("source", parentCls != null ? "app" : "clsp");
					superChain.add(entry);
					if (parentCls != null) {
						ClassNode parentCn = parentCls.getClassNode();
						for (ArgType i : parentCn.getInterfaces()) {
							if (i.isObject()) {
								inheritedIfs.add(i.getObject());
							}
						}
						sup = parentCn.getSuperClass();
					} else {
						break;
					}
				}
				inheritedIfs.removeAll(directIfs);
				body.put("super_chain", superChain);
				body.put("interfaces_direct", new ArrayList<>(directIfs));
				body.put("interfaces_inherited", new ArrayList<>(inheritedIfs));
			}

			if (wantDown) {
				DownResult down = collectDown(decompiler, targetRaw, transitive, maxResults);
				body.put("target_kind", down.targetKind);
				body.put("subclasses", down.subclasses);
				body.put("implementors", down.implementors);
				Map<String, Object> stats = new LinkedHashMap<>();
				stats.put("subclass_count", down.subclasses.size());
				stats.put("implementor_count", down.implementors.size());
				stats.put("truncated", down.truncated);
				if (transitive) {
					stats.put("transitive_total", down.transitiveTotal);
				}
				if (down.auxFiltered > 0) {
					stats.put("aux_filtered", down.auxFiltered);
				}
				body.put("down_stats", stats);
			}
			return body;
		});
	}

	private static final class DownResult {
		final List<Map<String, Object>> subclasses = new ArrayList<>();
		final List<Map<String, Object>> implementors = new ArrayList<>();
		String targetKind = "unknown"; // class | interface | unknown
		int transitiveTotal;
		int auxFiltered;
		boolean truncated;
	}

	private DownResult collectDown(JadxDecompiler decompiler, String targetRaw, boolean transitive, int maxResults) {
		DownResult out = new DownResult();
		ClspGraph clsp = decompiler.getRoot().getClsp();
		ClspClass clspTarget = clsp.isClsKnown(targetRaw) ? clsp.getClsDetails(ArgType.object(targetRaw)) : null;
		// One classification, applied to every entry: subtypes of an interface land under
		// `implementors`, subtypes of a class land under `subclasses`. JLS forbids the mixed case.
		boolean targetIsInterface = clspTarget != null && clspTarget.isInterface();
		out.targetKind = clspTarget == null ? "unknown" : (targetIsInterface ? "interface" : "class");

		if (transitive) {
			List<String> impls = clsp.getImplementations(targetRaw);
			out.transitiveTotal = impls.size();
			List<Map<String, Object>> bucket = targetIsInterface ? out.implementors : out.subclasses;
			for (String name : impls) {
				JavaClass jc = session.findClass(name);
				// Drop aux: framework-internal subtypes (e.g. android.app.AccountAuthenticatorActivity
				// for `android.app.Activity`) would dilute "who in MY code extends X?".
				// jc==null entries are pure clsp names — keep them so the answer still works without
				// any aux jar loaded (the user can choose to add one to get richer info).
				if (jc != null && !session.isAppClass(jc)) {
					out.auxFiltered++;
					continue;
				}
				if (bucket.size() >= maxResults) {
					out.truncated = true;
					continue;
				}
				bucket.add(baseEntry(name, jc));
			}
		} else {
			// Walk app-only: aux classes that declare the target as super never make the result list.
			for (JavaClass cls : session.appClasses()) {
				if (out.subclasses.size() + out.implementors.size() >= maxResults) {
					out.truncated = true;
					break;
				}
				ClassNode cn = cls.getClassNode();
				ArgType sup = cn.getSuperClass();
				if (sup != null && sup.isObject() && sup.getObject().equals(targetRaw)) {
					out.subclasses.add(baseEntry(cls.getRawName(), cls));
				} else if (isDeclaredInterfaceImpl(cn, targetRaw)) {
					out.implementors.add(baseEntry(cls.getRawName(), cls));
				}
			}
		}
		return out;
	}

	private static boolean isDeclaredInterfaceImpl(ClassNode cn, String targetRaw) {
		for (ArgType i : cn.getInterfaces()) {
			if (i.isObject() && i.getObject().equals(targetRaw)) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, Object> baseEntry(String rawName, JavaClass jc) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("fqn", jc != null ? jc.getFullName() : rawName);
		if (jc != null && !jc.getFullName().equals(jc.getRawName())) {
			entry.put("raw_fqn", jc.getRawName());
		}
		entry.put("source", jc != null ? "app" : "clsp");
		if (jc != null) {
			entry.put("target", jc.getFullName());
		}
		return entry;
	}
}
