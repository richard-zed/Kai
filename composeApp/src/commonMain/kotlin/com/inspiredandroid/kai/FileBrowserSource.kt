package com.inspiredandroid.kai

import io.github.vinceglb.filekit.PlatformFile

/** Largest file the built-in editor will pull into memory. */
const val MAX_BROWSER_TEXT_BYTES: Int = 512_000

/**
 * A pull-based reader over a file's bytes, for copying it somewhere else without
 * ever holding all of it in memory.
 *
 * [readChunk] fills a caller-owned buffer instead of allocating per chunk, so a
 * full copy costs one buffer regardless of file size. Callers should reuse a single
 * array across calls.
 */
interface FileReadStream {
    /** Size in bytes when the source can report it, for naming and progress. */
    val sizeBytes: Long?

    /**
     * Fills [into] and returns the byte count, or -1 at end of file. A short count
     * before EOF is legal; callers loop until -1.
     */
    suspend fun readChunk(into: ByteArray): Int

    /** Releases the handle. Safe to call more than once. */
    fun close()
}

/**
 * Why a file could — or could not — be shown as text. A bare null would collapse
 * "too big", "not text" and "gone" into one indistinguishable outcome, which is
 * what the browser used to report to the user.
 */
sealed interface TextFileResult {
    /**
     * Decoded content. [editable] is false when the bytes are not valid UTF-8 and were
     * decoded lossily on request: the buffer contains replacement characters, so writing
     * it back would destroy the original bytes.
     */
    data class Text(val content: String, val editable: Boolean = true) : TextFileResult

    /** Not valid UTF-8 text. A forced read still decodes it, read-only. */
    data object Binary : TextFileResult

    /** Beyond the editor's cap; [sizeBytes] is the real size, for the message. */
    data class TooLarge(val sizeBytes: Long) : TextFileResult

    /** Missing, not a regular file, or the read failed. */
    data object Unreadable : TextFileResult
}

/**
 * A browsable file tree behind an absolute-path API. Both Linux environments
 * expose one — the chat Alpine sandbox and Kai Build's Debian — so the file
 * browser UI can be pointed at either without knowing whose files it shows.
 *
 * Paths are the ones the user sees in that environment (guest paths); the
 * implementation maps them to wherever the files actually live on the host.
 */
interface FileBrowserSource {
    /** Directories first, then case-insensitive by name. Empty when [path] is not a readable directory. */
    suspend fun listDirectory(path: String): List<SandboxFileEntry>

    /**
     * Reads [path] as text. [force] decodes non-UTF-8 bytes lossily instead of
     * reporting [TextFileResult.Binary]; it does not lift [maxBytes].
     */
    suspend fun readTextFile(
        path: String,
        maxBytes: Int = MAX_BROWSER_TEXT_BYTES,
        force: Boolean = false,
    ): TextFileResult

    /**
     * Opens [path] for verbatim copying, with no size cap and no decoding. This is the
     * path for copying a file off the device: [readTextFile] caps at
     * [MAX_BROWSER_TEXT_BYTES] and lossily replaces non-UTF-8 bytes, so it cannot
     * round-trip a binary.
     *
     * Unlike a read-into-memory call this never holds the whole file resident, so a
     * multi-gigabyte file cannot OOM the process. Defaults to null, meaning "this
     * source cannot produce raw bytes". Sources that back onto a real file override
     * it; that keeps adding the method from breaking every other implementer.
     */
    suspend fun openReadStream(path: String): FileReadStream? = null

    suspend fun writeTextFile(path: String, content: String): Boolean

    /** Hands the file to another app on the device. */
    suspend fun openFile(path: String): Result<Unit>

    suspend fun deleteEntry(path: String, recursive: Boolean): Boolean

    /** Renames within the same directory; returns the new path. Fails with `"collision"` if taken. */
    suspend fun renameEntry(path: String, newName: String): Result<String>

    /**
     * Streams a file picked from device storage into [directoryPath]. Returns the new
     * guest path; the name is suffixed rather than overwriting an existing file.
     */
    suspend fun importFile(directoryPath: String, source: PlatformFile): Result<String>
}

/** Used by platforms that have no Linux environment at all. */
object NoOpFileBrowserSource : FileBrowserSource {
    override suspend fun listDirectory(path: String): List<SandboxFileEntry> = emptyList()
    override suspend fun readTextFile(path: String, maxBytes: Int, force: Boolean): TextFileResult = TextFileResult.Unreadable
    override suspend fun writeTextFile(path: String, content: String): Boolean = false
    override suspend fun openFile(path: String): Result<Unit> = Result.failure(UnsupportedOperationException("File browsing is Android-only"))

    override suspend fun deleteEntry(path: String, recursive: Boolean): Boolean = false
    override suspend fun renameEntry(path: String, newName: String): Result<String> = Result.failure(UnsupportedOperationException("File browsing is Android-only"))
    override suspend fun importFile(directoryPath: String, source: PlatformFile): Result<String> = Result.failure(UnsupportedOperationException("File browsing is Android-only"))
}
