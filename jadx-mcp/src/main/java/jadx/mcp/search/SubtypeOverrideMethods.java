package jadx.mcp.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import jadx.api.JavaMethod;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.RootNode;

/**
 * Collects override-related methods whose declaring type is the anchor's declaring type or a subtype.
 */
public final class SubtypeOverrideMethods {

	private static final Comparator<JavaMethod> ORDER = Comparator
			.comparing((JavaMethod m) -> m.getDeclaringClass().getFullName())
			.thenComparing(JavaMethod::getFullName);

	private SubtypeOverrideMethods() {
	}

	@NotNull
	public static List<JavaMethod> collectCandidates(RootNode root, JavaMethod anchor) {
		List<JavaMethod> related = anchor.getOverrideRelatedMethods();
		ArgType anchorType = anchor.getDeclaringClass().getClassNode().getType();
		if (related.isEmpty()) {
			return List.of(anchor);
		}
		Set<JavaMethod> unique = new LinkedHashSet<>();
		for (JavaMethod m : related) {
			ArgType declType = m.getDeclaringClass().getClassNode().getType();
			if (ArgType.isInstanceOf(root, declType, anchorType)) {
				unique.add(m);
			}
		}
		if (unique.isEmpty()) {
			return List.of(anchor);
		}
		List<JavaMethod> list = new ArrayList<>(unique);
		list.sort(ORDER);
		return list;
	}
}
