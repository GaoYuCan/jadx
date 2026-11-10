package jadx.mcp.format;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * All {@link RefEntry} rows for one decompiled class, indexed by {@code ref_id}.
 * <p>
 * Built once per class by {@link RefTableBuilder} and cached in {@link RefTableCache}. The {@code line} field
 * inside {@link RefEntry} is the same line you see in the {@code decompile_code} output.
 */
public final class RefTable {

	private final List<RefEntry> entries;
	private final Map<String, RefEntry> byId;

	public RefTable(List<RefEntry> entries) {
		this.entries = List.copyOf(entries);
		this.byId = new HashMap<>(entries.size() * 2);
		for (RefEntry e : entries) {
			byId.put(e.refId(), e);
		}
	}

	public List<RefEntry> entries() {
		return entries;
	}

	public @Nullable RefEntry get(String refId) {
		return byId.get(refId);
	}
}
