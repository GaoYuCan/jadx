package jadx.mcp.format;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;

/**
 * All {@link RefEntry} rows for one decompiled class, indexed for cheap lookup by {@code ref_id} and by raw offset.
 * <p>
 * Built once per class by {@link RefTableBuilder} and cached in {@link RefTableCache}. The {@code line} field
 * inside {@link RefEntry} is the same line you see in the {@code decompile_code} output.
 */
public final class RefTable {

	private final String classFqn;
	private final List<RefEntry> entries;
	/** ref_id -> entry for O(1) {@code resolve_ref}. */
	private final Map<String, RefEntry> byId;
	/** defPos -> entry; used by search hits to attach a ref_id when the hit covers a known reference. */
	private final NavigableMap<Integer, RefEntry> byDefPos;
	/** line -> entries on that line. */
	private final Map<Integer, List<RefEntry>> byLine;

	public RefTable(String classFqn, List<RefEntry> entries) {
		this.classFqn = classFqn;
		this.entries = Collections.unmodifiableList(entries);
		this.byId = new HashMap<>(entries.size() * 2);
		this.byDefPos = new TreeMap<>();
		this.byLine = new HashMap<>();
		for (RefEntry e : entries) {
			byId.put(e.refId(), e);
			byDefPos.put(e.defPos(), e);
			byLine.computeIfAbsent(e.line(), k -> new ArrayList<>()).add(e);
		}
	}

	public String classFqn() {
		return classFqn;
	}

	public List<RefEntry> entries() {
		return entries;
	}

	public @Nullable RefEntry get(String refId) {
		return byId.get(refId);
	}

	public List<RefEntry> onLine(int line) {
		return byLine.getOrDefault(line, List.of());
	}

	/**
	 * Find the entry whose def_pos most closely covers the given raw offset, or {@code null} if none of the
	 * entries are within {@code maxDistance} characters.
	 */
	public @Nullable RefEntry findNearOffset(int offset, int maxDistance) {
		Map.Entry<Integer, RefEntry> floor = byDefPos.floorEntry(offset);
		if (floor != null && offset - floor.getKey() <= maxDistance) {
			return floor.getValue();
		}
		Map.Entry<Integer, RefEntry> ceiling = byDefPos.ceilingEntry(offset);
		if (ceiling != null && ceiling.getKey() - offset <= maxDistance) {
			return ceiling.getValue();
		}
		return null;
	}
}
