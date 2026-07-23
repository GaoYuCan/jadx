package jadx.mcp.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jadx.api.ICodeInfo;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.JavaVariable;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.NodeDeclareRef;

/** Builds the optional variable sidecar returned by {@code decompile_code}. */
public final class VariableTableBuilder {

	public static List<VariableEntry> build(JadxDecompiler decompiler, JavaClass javaClass) {
		ICodeInfo codeInfo = javaClass.getCodeInfo();
		if (!codeInfo.hasMetadata()) {
			return List.of();
		}
		String code = codeInfo.getCodeStr();
		Map<String, VariableEntry> unique = new LinkedHashMap<>();
		for (Map.Entry<Integer, ICodeAnnotation> entry : new TreeMap<>(codeInfo.getCodeMetadata().getAsMap()).entrySet()) {
			ICodeAnnotation annotation = entry.getValue();
			if (!isVariableDeclaration(annotation)) {
				continue;
			}
			JavaNode node = decompiler.getJavaNodeByCodeAnnotation(codeInfo, annotation);
			if (!(node instanceof JavaVariable variable) || variable.getName() == null) {
				continue;
			}
			JavaMethod method = variable.getMth();
			String methodTarget = method.getDeclaringClass().getFullName() + "#"
					+ method.getMethodNode().getMethodInfo().getShortId();
			String variableId = variableId(variable.getReg(), variable.getSsa());
			String key = methodTarget + '@' + variableId;
			int pos = entry.getKey();
			int line = lineNumberAt(code, pos);
			int col = columnAt(code, pos);
			unique.putIfAbsent(key, new VariableEntry(
					variableId,
					variable.getName(),
					variable.getType().toString(),
					line,
					col,
					methodTarget));
		}
		return new ArrayList<>(unique.values());
	}

	private static boolean isVariableDeclaration(ICodeAnnotation annotation) {
		if (!(annotation instanceof NodeDeclareRef declaration)) {
			return false;
		}
		ICodeNodeRef node = declaration.getNode();
		return node.getAnnType() == ICodeAnnotation.AnnType.VAR;
	}

	public static String variableId(int reg, int ssa) {
		return "r" + reg + "v" + ssa;
	}

	private static int lineNumberAt(String code, int pos) {
		int line = 1;
		for (int i = 0; i < pos && i < code.length(); i++) {
			if (code.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	private static int columnAt(String code, int pos) {
		int lineStart = code.lastIndexOf('\n', Math.max(0, pos - 1));
		return pos - lineStart - 1;
	}

	private VariableTableBuilder() {
	}
}
