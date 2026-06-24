package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.mcp.JadxSession;
import jadx.mcp.util.ToolException;

/**
 * Resolves a {@link Target} against an active {@link JadxSession}.
 * <p>
 * Class-only targets perform a straight FQN lookup. Method targets either match exactly by jvm short-id when
 * the descriptor is given, or, when only a method name is provided, return a single match if unambiguous and
 * raise a structured {@link ToolException} (code {@code AMBIGUOUS}) listing all overloads otherwise.
 */
public final class TargetResolver {

	private TargetResolver() {
	}

	@NotNull
	public static JavaClass resolveClass(JadxSession session, Target target) {
		JavaClass cls = session.findClass(target.classFqn());
		if (cls == null) {
			throw ToolException.notFound("class", target.classFqn());
		}
		return cls;
	}

	@NotNull
	public static JavaMethod resolveMethod(JadxSession session, Target target) {
		if (!target.isMethod()) {
			throw ToolException.invalidArg("target", "expected a method target ('com.foo.A#bar' or with descriptor)");
		}
		JavaClass cls = resolveClass(session, target);
		if (target.shortId() != null) {
			JavaMethod m = cls.searchMethodByShortId(target.shortId());
			if (m == null) {
				throw ToolException.notFound("method", target.toString());
			}
			return m;
		}
		// no descriptor: match by name, complain if ambiguous
		List<JavaMethod> matches = new ArrayList<>();
		for (JavaMethod m : cls.getMethods()) {
			if (m.getName().equals(target.memberName())) {
				matches.add(m);
			}
		}
		if (matches.isEmpty()) {
			throw ToolException.notFound("method", target.toString());
		}
		if (matches.size() == 1) {
			return matches.get(0);
		}
		List<Map<String, Object>> candidates = new ArrayList<>(matches.size());
		for (JavaMethod m : matches) {
			String shortId = m.getMethodNode().getMethodInfo().getShortId();
			String descriptor = shortId.substring(shortId.indexOf('('));
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("descriptor", descriptor);
			entry.put("full_target", target.classFqn() + "#" + m.getName() + descriptor);
			candidates.add(entry);
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("class_fqn", target.classFqn());
		details.put("method_name", target.memberName());
		details.put("candidates", candidates);
		details.put("hint", "Re-issue the request with one of `full_target` strings to disambiguate.");
		throw ToolException.ambiguous("method", target.toString(), details);
	}
}
