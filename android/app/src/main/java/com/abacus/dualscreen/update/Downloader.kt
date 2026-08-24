package com.abacus.dualscreen.update

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Fetching the artefact, over a handheld's Wi-Fi.
 *
 * Everything here is shaped by one assumption: the connection will drop halfway through at least
 * once. So the file is written to a `.part` beside its destination, the byte count already on disk
 * goes out as a `Range` header on the next attempt, and a retry continues rather than starting the
 * four megabytes again. A server that ignores the range answers 200 instead of 206 and the file
 * starts over — handled, not assumed away.
 *
 * Cancellation is cooperative: the loop checks a flag between buffers, so it stops within a few
 * kilobytes and leaves the partial file where it is. Pressing Update again resumes from there.
 *
 * Blocks. Call from a background thread.
 */
object Downloader {

    /** Under cacheDir, so Android may reclaim it under storage pressure and nothing is left behind. */
    private const val FOLDER = "updates"

    private const val BUFFER = 64 * 1024

    /** Progress is reported no more often than this. A progress bar does not need 4000 updates. */
    private const val PROGRESS_MS = 120L

    /** Headroom over the download itself, because the installer needs room to unpack too. */
    private const val SPACE_FACTOR = 2.5

    sealed interface Outcome {
        data class Done(val file: File) : Outcome
        data class Broken(val failure: Failure) : Outcome
    }

    fun folder(context: Context): File = File(context.cacheDir, FOLDER).apply { mkdirs() }

    /** Where this update lands. Deterministic, so a resumed download finds its own partial file. */
    fun fileFor(context: Context, update: Update): File {
        val safe = (update.sourceId + "-" + update.version.text + "-" + update.assetName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(folder(context), safe)
    }

    /**
     * Download [update], reporting progress as it goes.
     *
     * @param cancelled polled between buffers; return true to stop.
     * @param onProgress bytes so far, and the total expected, on the calling thread.
     */
    fun download(
        context: Context,
        update: Update,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Outcome {
        if (!Http.online(context)) return Outcome.Broken(Failure(UpdateError.OFFLINE))

        val target = fileFor(context, update)
        val partial = File(target.path + ".part")

        // A finished, verified copy from an earlier run is worth reusing rather than fetching again.
        if (target.isFile && target.length() > 0 && matches(target, update)) {
            onProgress(target.length(), target.length())
            return Outcome.Done(target)
        }
        sweep(context, keep = setOf(target.name, partial.name))

        var have = if (partial.isFile) partial.length() else 0L
        if (update.size > 0 && have > update.size) {
            // A partial longer than the asset is not this asset. Start again.
            partial.delete()
            have = 0L
        }

        if (update.size > 0) {
            val needed = ((update.size - have) * SPACE_FACTOR).toLong()
            val free = runCatching { folder(context).usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (free in 0 until needed)
                return Outcome.Broken(Failure(UpdateError.NO_SPACE, "needs " + bytes(needed)))
        }

        val headers = if (have > 0) mapOf("Range" to ("bytes=" + have + "-")) else emptyMap()

        val connection = try {
            Http.get(update.url, headers)
        } catch (error: Exception) {
            return Outcome.Broken(Http.classify(error))
        }

        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit // resuming, as asked

                HttpURLConnection.HTTP_OK -> {
                    // The range was ignored: this response is the whole file from byte zero.
                    have = 0L
                    partial.delete()
                }

                HttpURLConnection.HTTP_NOT_FOUND ->
                    return Outcome.Broken(Failure(UpdateError.NOT_FOUND, update.assetName))

                416 -> {
                    // Range beyond the end: the partial is stale. Drop it so a retry restarts.
                    partial.delete()
                    return Outcome.Broken(Failure(UpdateError.DOWNLOAD_FAILED, "stale partial file"))
                }

                else -> return Outcome.Broken(Failure(UpdateError.DOWNLOAD_FAILED, "HTTP " + code))
            }

            val expected = when {
                update.size > 0 -> update.size
                connection.contentLength > 0 -> have + connection.contentLength
                else -> 0L
            }

            connection.inputStream.use { input ->
                FileOutputStream(partial, have > 0).use { output ->
                    val buffer = ByteArray(BUFFER)
                    var announced = 0L

                    while (true) {
                        if (cancelled()) return Outcome.Broken(Failure(UpdateError.CANCELLED))

                        val read = input.read(buffer)
                        if (read < 0) break

                        output.write(buffer, 0, read)
                        have += read

                        val now = System.currentTimeMillis()
                        if (now - announced >= PROGRESS_MS) {
                            announced = now
                            onProgress(have, expected)
                        }
                    }
                    output.flush()
                }
            }

            onProgress(have, expected)

            if (expected > 0 && have != expected)
                return Outcome.Broken(
                    Failure(
                        UpdateError.DOWNLOAD_FAILED,
                        "got " + bytes(have) + " of " + bytes(expected),
                    )
                )

            // Checked before the rename, so a bad file is never mistaken for a finished one.
            update.sha256?.let { want ->
                val got = runCatching { AppSource.sha256(partial) }.getOrNull()
                if (got == null || !got.equals(want, ignoreCase = true)) {
                    partial.delete()
                    return Outcome.Broken(Failure(UpdateError.CHECKSUM, got))
                }
            }

            target.delete()
            if (!partial.renameTo(target))
                return Outcome.Broken(Failure(UpdateError.DOWNLOAD_FAILED, "could not finish the file"))

            return Outcome.Done(target)
        } catch (error: IOException) {
            return Outcome.Broken(Failure(UpdateError.DOWNLOAD_FAILED, error.message))
        } catch (error: Exception) {
            return Outcome.Broken(Failure(UpdateError.DOWNLOAD_FAILED, error.message))
        } finally {
            connection.disconnect()
        }
    }

    /** True when a file already on disk is the one this update describes. */
    private fun matches(file: File, update: Update): Boolean {
        if (update.size > 0 && file.length() != update.size) return false
        val want = update.sha256 ?: return update.size > 0 // no digest: the length is all there is
        return runCatching { AppSource.sha256(file) }.getOrNull()?.equals(want, true) == true
    }

    /** Downloads left by earlier versions. The cache is not a place to keep things. */
    private fun sweep(context: Context, keep: Set<String>) {
        folder(context).listFiles()?.forEach { file ->
            if (file.name !in keep) file.delete()
        }
    }

    fun bytes(count: Long): String = when {
        count >= 1 shl 20 -> String.format("%.1f MB", count / 1048576.0)
        count >= 1 shl 10 -> String.format("%.0f KB", count / 1024.0)
        else -> count.toString() + " B"
    }
}
