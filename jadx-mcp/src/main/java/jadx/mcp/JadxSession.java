package jadx.mcp;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import jadx.api.JadxDecompiler;
import jadx.mcp.dex.DexProject;
import jadx.mcp.format.RefTableCache;
import jadx.mcp.util.ToolException;

/** Owns the active DEX index and serializes operations that create a local JADX context. */
public final class JadxSession implements Closeable {
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final RefTableCache refTableCache = new RefTableCache();

	private File inputFile;
	private DexProject project;

	public OpenResult load(File file) throws IOException {
		lock.writeLock().lock();
		try {
			if (project != null && sameFile(inputFile, file)) {
				return new OpenResult(true, project.classCount(), null);
			}
			DexProject next = new DexProject(file);
			File previous = inputFile;
			closeProject();
			project = next;
			inputFile = file;
			return new OpenResult(false, next.classCount(), previous);
		} finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public void close() {
		closeAndSnapshot();
	}

	@Nullable
	public File closeAndSnapshot() {
		lock.writeLock().lock();
		try {
			File previous = inputFile;
			closeProject();
			return previous;
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void closeProject() {
		if (project != null) {
			try {
				project.close();
			} finally {
				project = null;
				inputFile = null;
				refTableCache.invalidateAll();
			}
		}
	}

	@Nullable
	public File getInputFile() {
		lock.readLock().lock();
		try {
			return inputFile;
		} finally {
			lock.readLock().unlock();
		}
	}

	public <T> T read(Function<DexProject, T> action) {
		lock.readLock().lock();
		try {
			return action.apply(requireProject());
		} finally {
			lock.readLock().unlock();
		}
	}

	/** Resolve the scope and use its local decompiler under the same session lock. */
	public <T> T withDecompiler(Function<DexProject, Set<String>> scope, Function<JadxDecompiler, T> action) {
		lock.writeLock().lock();
		try {
			DexProject active = requireProject();
			try (JadxDecompiler decompiler = active.decompiler(scope.apply(active))) {
				return action.apply(decompiler);
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	public RefTableCache refCache() {
		return refTableCache;
	}

	private DexProject requireProject() {
		if (project == null) {
			throw new ToolException(ToolException.Code.NOT_LOADED,
					"No project is loaded. Call `open_file` with an APK or DEX path first.");
		}
		return project;
	}

	private static boolean sameFile(File first, File second) {
		try {
			return first.getCanonicalFile().equals(second.getCanonicalFile());
		} catch (IOException e) {
			return first.getAbsoluteFile().toPath().normalize()
					.equals(second.getAbsoluteFile().toPath().normalize());
		}
	}

	public record OpenResult(boolean alreadyLoaded, int classCount, @Nullable File previousInputFile) {
	}
}
