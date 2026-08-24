package com.abacus.dualscreen.update

import android.content.Context

/**
 * Deciding whether there is something newer, and saying so honestly when it cannot tell.
 *
 * Pure logic over an [UpdateSource]: no UI, no preferences except the ETag it is handed, and no
 * knowledge of what the artefact is. The awkward, project-specific part — reading a version out of
 * a release written for humans — belongs to the source.
 *
 * Blocks. Call from a background thread.
 */
object UpdateChecker {

    sealed interface Outcome {
        data class Available(val update: Update, val etag: String?) : Outcome

        /** Nothing newer. [latest] is what was found, for a screen that wants to say so. */
        data class UpToDate(val latest: Version?, val etag: String?) : Outcome

        data class Broken(val failure: Failure) : Outcome
    }

    fun check(
        context: Context,
        source: UpdateSource,
        channel: Channel,
        etag: String? = null,
    ): Outcome {
        if (!Http.online(context)) return Outcome.Broken(Failure(UpdateError.OFFLINE))

        val answer = when (val reply = GitHub.releases(source.repo, etag = etag)) {
            is GitHub.Answer.Broken -> return Outcome.Broken(reply.failure)

            // Nothing has been published since the last look. The caller keeps what it had.
            GitHub.Answer.NotModified -> return Outcome.UpToDate(null, etag)

            is GitHub.Answer.Ok -> reply
        }

        val releases = answer.releases
        val newEtag = answer.etag
        val installed = source.installedVersion()

        /*
         * Newest first, and stop at the first release that names a version.
         *
         * Not "the highest version anywhere in the list": these releases carry five projects at
         * once, and an app-unchanged release honestly reports the app version it is carrying. If
         * the newest release says the app is 0.8.0 while a device is running 0.14.0, the correct
         * answer is "nothing to do" — searching backwards for a bigger number would find 0.14.0 in
         * an older release and offer somebody the build they already have.
         */
        var sawAsset = false

        for (release in releases) {
            if (!channel.accepts(release)) continue

            val asset = release.asset(source.artifactName)
                ?: release.assetEndingIn(source.artifactSuffix)
                ?: continue
            sawAsset = true

            // Fetched only for the release actually being considered — one small request, not one
            // per release, and none at all for a repository that publishes no manifests.
            val manifest = source.manifestName
                ?.let { name -> release.asset(name) }
                ?.let { GitHub.text(it.url) }
                ?.let { UpdateManifest.parse(it) }

            val version = source.versionOf(release, manifest) ?: continue

            // A manifest may name a different file than the conventional one.
            val payload = manifest?.artifact?.let { release.asset(it) } ?: asset

            // A pre-release version is not offered on a channel that did not ask for one, even if
            // whoever published it forgot to tick the box on GitHub.
            if (!channel.accepts(version)) continue

            if (installed != null && version <= installed)
                return Outcome.UpToDate(version, newEtag)

            /*
             * The same bytes we are already running.
             *
             * Cheap insurance against this repository's habit of carrying an unchanged APK forward
             * into the next release: if the digest matches the installed APK, it is the same build
             * whatever the title claims. Only reached when a release already looked newer, so the
             * hash of our own APK is computed at most once per check.
             */
            val expected = manifest?.sha256 ?: payload.sha256
            if (expected != null && source is AppSource && expected == source.installedFingerprint())
                return Outcome.UpToDate(installed, newEtag)

            return Outcome.Available(
                Update(
                    sourceId = source.id,
                    version = version,
                    versionCode = manifest?.versionCode,
                    tag = release.tag,
                    title = release.title,
                    notes = manifest?.notes?.takeIf { it.isNotBlank() } ?: release.notes,
                    assetName = payload.name,
                    url = payload.url,
                    size = manifest?.size ?: payload.size,
                    sha256 = expected,
                    preRelease = release.preRelease || version.isPreRelease,
                    publishedAt = release.publishedAt,
                ),
                newEtag,
            )
        }

        /*
         * Nothing usable was found, and which kind of nothing matters.
         *
         * A repository with releases that carry no APK is misconfigured; a repository whose
         * releases carry one but never say what version it is cannot be compared against. Both are
         * reported as themselves rather than as "up to date", because silently claiming to be
         * current is how an updater stops being trusted.
         */
        if (releases.isEmpty()) return Outcome.Broken(Failure(UpdateError.NOT_FOUND, "no releases"))
        if (!sawAsset) return Outcome.Broken(Failure(UpdateError.NO_ASSET, source.artifactName))
        return Outcome.Broken(Failure(UpdateError.UNKNOWN_VERSION, releases.firstOrNull()?.tag))
    }
}
