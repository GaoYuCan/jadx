package jadx.mcp;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaNode;
import jadx.api.args.UseSourceNameAsClassNameAlias;
import jadx.api.impl.AnnotatedCodeWriter;
import jadx.mcp.format.RefTableCache;
import jadx.mcp.util.ToolException;
import jadx.plugins.tools.JadxExternalPluginsLoader;

/**
 * Owns the singleton {@link JadxDecompiler} for the lifetime of the MCP server process.
 * <p>
 * Concurrency model: a {@link ReentrantReadWriteLock} guards the decompiler. Tool handlers acquire the read lock
 * (multiple tools may run concurrently against an already-loaded decompiler), while {@link #reload()} / {@link #close()}
 * acquire the write lock.
 * <p>
 * The session also owns shared caches that live as long as the decompiler (e.g. RefTable, FQN -> JavaClass).
 */
public final class JadxSession implements Closeable {

	private static final Logger LOG = LoggerFactory.getLogger(JadxSession.class);

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final RefTableCache refTableCache = new RefTableCache();
	// keyed by FQN (alias or raw); concurrent because populated lazily under the read lock by findClass()
	private final ConcurrentMap<String, JavaClass> fqnIndex = new ConcurrentHashMap<>();
	// FQNs (alias + raw) of classes that came from auxInputs; used by isAppClass() to hide them from
	// "list-shaped" tool output. Built once at openDecompilerLocked() and read concurrently afterwards.
	private final Set<String> auxClassFqns = ConcurrentHashMap.newKeySet();

	private File inputFile;
	private List<File> auxInputs = Collections.emptyList();
	private boolean skipResources;
	private int threadsCount;
	// Distinct aux class count, set once during openDecompilerLocked. Tracked independently of
	// auxClassFqns.size() because the set merges alias + raw FQN entries when they're identical.
	private int auxClassCount;

	private JadxDecompiler decompiler;

	public void load(File inputFile, List<File> auxInputs, boolean skipResources, int threadsCount) {
		lock.writeLock().lock();
		try {
			this.inputFile = inputFile;
			this.auxInputs = auxInputs == null ? Collections.emptyList() : List.copyOf(auxInputs);
			this.skipResources = skipResources;
			this.threadsCount = threadsCount;
			openDecompilerLocked();
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void reload() {
		lock.writeLock().lock();
		try {
			closeDecompilerLocked();
			openDecompilerLocked();
		} finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public void close() {
		closeAndSnapshot();
	}

	/**
	 * Close the decompiler and return what was loaded just before the close — atomically, under the write lock.
	 * This avoids a TOCTOU race where a caller reads {@link #isLoaded()} / {@link #getInputFile()} separately
	 * from the close call and another writer slips in between.
	 */
	public CloseSnapshot closeAndSnapshot() {
		lock.writeLock().lock();
		try {
			boolean wasLoaded = decompiler != null;
			File previous = inputFile;
			closeDecompilerLocked();
			// Forget the input metadata so getInputFile()/isLoaded() report the truthful "nothing loaded" state.
			// reload() doesn't reach here because it calls closeDecompilerLocked() directly, preserving the metadata.
			inputFile = null;
			auxInputs = Collections.emptyList();
			skipResources = false;
			threadsCount = 0;
			return new CloseSnapshot(wasLoaded, previous);
		} finally {
			lock.writeLock().unlock();
		}
	}

	/** Result of an atomic {@link #closeAndSnapshot()} call. */
	public static final class CloseSnapshot {
		public final boolean wasLoaded;
		public final @Nullable File previousInputFile;

		CloseSnapshot(boolean wasLoaded, @Nullable File previousInputFile) {
			this.wasLoaded = wasLoaded;
			this.previousInputFile = previousInputFile;
		}
	}

	private void openDecompilerLocked() {
		if (auxInputs.isEmpty()) {
			LOG.info("Loading jadx project from {}", inputFile);
		} else {
			LOG.info("Loading jadx project from {} (with {} aux input(s): {})",
					inputFile, auxInputs.size(), auxInputs);
		}
		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(inputFile);
		for (File aux : auxInputs) {
			args.getInputFiles().add(aux);
		}
		args.setSkipResources(skipResources);
		if (threadsCount > 0) {
			args.setThreadsCount(threadsCount);
		}
		args.setPluginLoader(new JadxExternalPluginsLoader());
		args.setFilesGetter(JadxMcpFilesGetter.INSTANCE);
		// AnnotatedCodeWriter -> we need code metadata for RefTable / xref / resolve_ref
		args.setCodeWriterProvider(AnnotatedCodeWriter::new);

		// MCP mode: lock alias == raw bytecode name on every non-Kotlin class.
		// kotlin-metadata / SDE plugins still rename Kotlin classes because they read source-truth
		// names baked into the APK by the compiler — that's known truth, not a guess.
		// Written explicitly (not relying on jadx defaults) so future jadx upgrades cannot silently
		// re-enable any of these and break tool I/O contracts.
		args.setDeobfuscationOn(false);
		args.setUseSourceNameAsClassNameAlias(UseSourceNameAsClassNameAlias.NEVER);
		args.setRenameCaseSensitive(false);
		args.setRenameValid(false);
		args.setRenamePrintable(false);

		// MCP mode: keep the on-wire structure as close to bytecode as possible. Each of these
		// "make jadx prettier for humans" passes folds methods / classes / constants into other
		// methods / classes / literals and erases their declaration site, which makes
		// `xrefs_to`, `method_overrides`, `inheritance_tree`, and `search_symbol` lie to the
		// LLM about what symbols actually exist in the dex. Caller bypasses for a single class
		// are not supported (would require unload+reload and trash the code cache) — pick the
		// less-pretty-but-honest version once at session creation.
		args.setMoveInnerClasses(false);          // keep $-style inner names; they round-trip with smali
		args.setInlineMethods(false);             // do not splice short methods into their callers
		args.setInlineAnonymousClasses(false);    // keep anonymous classes as their own ClassNode
		args.setReplaceConsts(false);             // keep `R.id.foo` as field reads, not literal values

		decompiler = new JadxDecompiler(args);
		decompiler.load();
		fqnIndex.clear();
		auxClassFqns.clear();
		auxClassCount = 0;
		refTableCache.invalidateAll();
		// Lower-cased base names of aux files, used to tag classes whose ClassNode.inputFileName came from one of them.
		// Substring match on lower case is the most resilient across jadx's input-file naming variants
		// (DEX uses File.getName() literally, JAR/CLASS uses "jar.jar:entry.class" form, etc.).
		Set<String> auxFileNames = auxInputs.stream()
				.map(f -> f.getName().toLowerCase())
				.collect(Collectors.toSet());
		for (JavaClass cls : decompiler.getClassesWithInners()) {
			fqnIndex.put(cls.getFullName(), cls);
			fqnIndex.put(cls.getRawName(), cls);
			if (!auxFileNames.isEmpty() && classFromAuxInput(cls, auxFileNames)) {
				auxClassFqns.add(cls.getFullName());
				auxClassFqns.add(cls.getRawName());
				auxClassCount++;
			}
		}
		LOG.info("Loaded {} classes from {}{}", fqnIndex.size(), inputFile,
				auxClassCount == 0 ? "" : " (" + auxClassCount + " aux classes)");
	}

	private static boolean classFromAuxInput(JavaClass cls, Set<String> auxFileNamesLower) {
		String src = cls.getClassNode().getInputFileName();
		if (src == null) {
			return false;
		}
		String lower = src.toLowerCase();
		for (String name : auxFileNamesLower) {
			if (lower.contains(name)) {
				return true;
			}
		}
		return false;
	}

	private void closeDecompilerLocked() {
		fqnIndex.clear();
		auxClassFqns.clear();
		auxClassCount = 0;
		refTableCache.invalidateAll();
		if (decompiler != null) {
			try {
				decompiler.close();
			} catch (Throwable t) {
				LOG.warn("Failed to close decompiler", t);
			}
			decompiler = null;
		}
	}

	/**
	 * Run a read-only action while holding the read lock.
	 */
	public <T> T read(Function<JadxDecompiler, T> action) {
		lock.readLock().lock();
		try {
			ensureLoaded();
			return action.apply(decompiler);
		} finally {
			lock.readLock().unlock();
		}
	}

	/**
	 * Run a read-only action that doesn't need the decompiler instance directly.
	 */
	public <T> T read(Supplier<T> action) {
		lock.readLock().lock();
		try {
			ensureLoaded();
			return action.get();
		} finally {
			lock.readLock().unlock();
		}
	}

	/**
	 * Look up a {@link JavaClass} by its fully qualified name (alias or raw).
	 * Must be called while holding the read lock (via {@link #read}).
	 */
	@Nullable
	public JavaClass findClass(String fqn) {
		JavaClass cached = fqnIndex.get(fqn);
		if (cached != null) {
			return cached;
		}
		// Cache may not include aliased names; fall back to API search
		JavaClass byOrig = decompiler.searchJavaClassByOrigFullName(fqn);
		if (byOrig != null) {
			fqnIndex.put(fqn, byOrig);
			return byOrig;
		}
		JavaClass byAlias = decompiler.searchJavaClassByAliasFullName(fqn);
		if (byAlias != null) {
			fqnIndex.put(fqn, byAlias);
			return byAlias;
		}
		return null;
	}

	public RefTableCache refCache() {
		return refTableCache;
	}

	@Nullable
	public File getInputFile() {
		return inputFile;
	}

	/** Snapshot of "is a project currently loaded?" — safe to call without holding any lock. */
	public boolean isLoaded() {
		return decompiler != null;
	}

	/** Class count currently loaded (top-level + inner classes); 0 when no project is loaded.
	 *  Must be called while holding the read lock (via {@link #read}) when isLoaded() may change. */
	public int loadedClassCount() {
		return decompiler == null ? 0 : decompiler.getClassesWithInners().size();
	}

	/** Aux-class count (subset of {@link #loadedClassCount()}); always 0 when no aux inputs were provided. */
	public int loadedAuxClassCount() {
		return auxClassCount;
	}

	/** App-class count = total - aux. */
	public int loadedAppClassCount() {
		return loadedClassCount() - loadedAuxClassCount();
	}

	public List<File> getAuxInputs() {
		return auxInputs;
	}

	/**
	 * True iff {@code jc} comes from the primary input (not from any aux input). Always true when no aux
	 * inputs were configured. Use this to hide aux classes from list-shaped tool output (list_classes,
	 * search_*, xrefs_to.uses, inheritance_tree.subclasses, ...). Tools that look up by explicit FQN
	 * (decompile_code, class_members, xrefs_to subject) must NOT filter on this — the user asked for the
	 * aux symbol explicitly.
	 */
	public boolean isAppClass(JavaClass jc) {
		if (auxClassFqns.isEmpty() || jc == null) {
			return true;
		}
		return !auxClassFqns.contains(jc.getFullName())
				&& !auxClassFqns.contains(jc.getRawName());
	}

	/** {@link #isAppClass(JavaClass)} for any {@link JavaNode}, walking to its top-level class. */
	public boolean isAppClass(JavaNode node) {
		if (auxClassFqns.isEmpty() || node == null) {
			return true;
		}
		JavaClass top = node instanceof JavaClass ? (JavaClass) node : node.getTopParentClass();
		return isAppClass(top);
	}

	/**
	 * Stream of every loaded class (top-level + inner) that is NOT from an aux input.
	 * Caller must hold the read lock (via {@link #read}).
	 */
	public Stream<JavaClass> appClassesStream() {
		if (auxClassFqns.isEmpty()) {
			return decompiler.getClassesWithInners().stream();
		}
		return decompiler.getClassesWithInners().stream().filter(this::isAppClass);
	}

	/** Materialised list of {@link #appClassesStream()}. */
	public List<JavaClass> appClasses() {
		if (auxClassFqns.isEmpty()) {
			return decompiler.getClassesWithInners();
		}
		List<JavaClass> all = decompiler.getClassesWithInners();
		List<JavaClass> out = new ArrayList<>(all.size());
		for (JavaClass cls : all) {
			if (isAppClass(cls)) {
				out.add(cls);
			}
		}
		return out;
	}

	/** App-only variant of {@link JadxDecompiler#getClasses()} (no inner classes). */
	public List<JavaClass> appClassesNoInner() {
		if (auxClassFqns.isEmpty()) {
			return decompiler.getClasses();
		}
		List<JavaClass> all = decompiler.getClasses();
		List<JavaClass> out = new ArrayList<>(all.size());
		for (JavaClass cls : all) {
			if (isAppClass(cls)) {
				out.add(cls);
			}
		}
		return out;
	}

	private void ensureLoaded() {
		if (decompiler == null) {
			throw new ToolException(ToolException.Code.NOT_LOADED,
					"No jadx project is currently loaded. Call `open_file` with the APK / DEX / JAR path first.");
		}
	}
}
