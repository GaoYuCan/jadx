package jadx.mcp.tools;

import jadx.mcp.util.ToolException;

/**
 * Parser for the unified {@code target} string accepted by {@code decompile_code} and {@code disassemble}.
 *
 * <ul>
 *   <li>{@code com.foo.A} -> class</li>
 *   <li>{@code com.foo.A$Inner} -> inner class (the {@code $} is preserved)</li>
 *   <li>{@code com.foo.A#bar} -> method by name (descriptor optional; multi-overload resolution is the caller's job)</li>
 *   <li>{@code com.foo.A#bar(I)V} -> method with precise jvm descriptor</li>
 *   <li>{@code com.foo.A#&lt;init&gt;()V} -> constructor</li>
 * </ul>
 */
public final class TargetParser {

	private TargetParser() {
	}

	public static Target parse(String input) throws ToolException {
		if (input == null || input.isBlank()) {
			throw ToolException.invalidArg("target",
					"must be a non-empty FQN like 'com.foo.A' or 'com.foo.A#bar(I)V'");
		}
		String s = input.trim();
		int hash = s.indexOf('#');
		if (hash < 0) {
			validateClassFqn(s);
			return Target.ofClass(s);
		}
		String classFqn = s.substring(0, hash);
		String memberPart = s.substring(hash + 1);
		validateClassFqn(classFqn);
		if (memberPart.isEmpty()) {
			throw ToolException.invalidArg("target",
					"missing method name after '#' in '" + input + "'");
		}
		int lp = memberPart.indexOf('(');
		if (lp < 0) {
			// name only, no descriptor
			validateMemberName(memberPart);
			return Target.ofMethod(classFqn, memberPart, null);
		}
		String name = memberPart.substring(0, lp);
		String descriptor = memberPart.substring(lp);
		if (name.isEmpty()) {
			throw ToolException.invalidArg("target",
					"missing method name before '(' in '" + input + "'");
		}
		validateMemberName(name);
		validateDescriptor(descriptor, input);
		return Target.ofMethod(classFqn, name, descriptor);
	}

	private static void validateClassFqn(String classFqn) throws ToolException {
		if (classFqn.isEmpty()) {
			throw ToolException.invalidArg("target", "empty class FQN");
		}
		for (int i = 0; i < classFqn.length(); i++) {
			char c = classFqn.charAt(i);
			boolean ok = Character.isJavaIdentifierPart(c) || c == '.' || c == '$';
			if (!ok) {
				throw ToolException.invalidArg("target",
						"invalid character '" + c + "' in class FQN '" + classFqn + "'");
			}
		}
	}

	private static void validateMemberName(String name) throws ToolException {
		// allow <init>, <clinit>, regular java identifiers, and synthetic names with $
		if (name.equals("<init>") || name.equals("<clinit>")) {
			return;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean ok = Character.isJavaIdentifierPart(c) || c == '$';
			if (!ok) {
				throw ToolException.invalidArg("target",
						"invalid character '" + c + "' in method name '" + name + "'");
			}
		}
	}

	private static void validateDescriptor(String descriptor, String fullInput) throws ToolException {
		// minimal sanity: must start with '(' and contain a matching ')'
		if (!descriptor.startsWith("(")) {
			throw ToolException.invalidArg("target",
					"descriptor must start with '(' in '" + fullInput + "'");
		}
		int rp = descriptor.indexOf(')');
		if (rp < 0 || rp == descriptor.length() - 1) {
			throw ToolException.invalidArg("target",
					"descriptor must contain ')' followed by return type in '" + fullInput + "'");
		}
	}
}
