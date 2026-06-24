package jadx.mcp.format;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.annotations.Nullable;

import jadx.api.ICodeInfo;

/**
 * Per-class {@link RefTable} cache, keyed by the class's FQN.
 * <p>
 * Each cached value also remembers the {@link ICodeInfo} identity it was built from so a stale RefTable can
 * be transparently rebuilt if jadx returns a new code info for the class (e.g. after a reload).
 */
public final class RefTableCache {

	private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

	public @Nullable RefTable getIfFresh(String classFqn, ICodeInfo expectedCodeInfo) {
		Entry e = cache.get(classFqn);
		if (e == null) {
			return null;
		}
		if (e.codeInfo != expectedCodeInfo) {
			cache.remove(classFqn, e);
			return null;
		}
		return e.table;
	}

	public @Nullable RefTable get(String classFqn) {
		Entry e = cache.get(classFqn);
		return e == null ? null : e.table;
	}

	public void put(String classFqn, ICodeInfo codeInfo, RefTable table) {
		cache.put(classFqn, new Entry(codeInfo, table));
	}

	public void invalidate(String classFqn) {
		cache.remove(classFqn);
	}

	public void invalidateAll() {
		cache.clear();
	}

	private static final class Entry {
		final ICodeInfo codeInfo;
		final RefTable table;

		Entry(ICodeInfo codeInfo, RefTable table) {
			this.codeInfo = codeInfo;
			this.table = table;
		}
	}
}
