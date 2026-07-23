package jadx.mcp.format;

/** One renameable method variable discovered in decompiled code metadata. */
public final class VariableEntry {
	private final String variableId;
	private final String name;
	private final String type;
	private final int line;
	private final int col;
	private final String methodTarget;

	public VariableEntry(String variableId, String name, String type, int line, int col, String methodTarget) {
		this.variableId = variableId;
		this.name = name;
		this.type = type;
		this.line = line;
		this.col = col;
		this.methodTarget = methodTarget;
	}

	public String variableId() {
		return variableId;
	}

	public String name() {
		return name;
	}

	public String type() {
		return type;
	}

	public int line() {
		return line;
	}

	public int col() {
		return col;
	}

	public String methodTarget() {
		return methodTarget;
	}
}
