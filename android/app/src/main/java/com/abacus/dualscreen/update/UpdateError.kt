package com.abacus.dualscreen.update

import androidx.annotation.StringRes
import com.abacus.dualscreen.R

/**
 * Everything that can go wrong, named.
 *
 * One enum rather than exceptions crossing layers, because each of these has a *different thing the
 * user should do next*, and that sentence is the whole value of reporting it. "Update failed" tells
 * somebody nothing; "GitHub is rate-limiting this network, try again later" tells them to stop
 * pressing the button.
 *
 * The wording lives in strings.xml and the technical detail — an exception message, an HTTP status —
 * is carried separately in [Failure], so it can be shown after the sentence rather than instead of
 * it.
 */
enum class UpdateError(@StringRes val message: Int) {

    /** No usable network at all. Checked before asking, so the answer is instant and honest. */
    OFFLINE(R.string.update_err_offline),

    /** The request left and did not come back: timeout, DNS, dropped Wi-Fi. */
    NETWORK(R.string.update_err_network),

    /** GitHub allows 60 unauthenticated requests an hour per address, and this one has run out. */
    RATE_LIMITED(R.string.update_err_rate_limited),

    /** The repository or its releases are not there: renamed, private, or misconfigured. */
    NOT_FOUND(R.string.update_err_not_found),

    /** GitHub answered with a 5xx. Nothing to fix at this end. */
    SERVER(R.string.update_err_server),

    /** Something answered, but not the JSON the API is documented to return. */
    MALFORMED(R.string.update_err_malformed),

    /** A release exists but carries no APK, so there is nothing to install. */
    NO_ASSET(R.string.update_err_no_asset),

    /** A release exists but nothing in it says which app version it is. */
    UNKNOWN_VERSION(R.string.update_err_unknown_version),

    /** The download did not finish. */
    DOWNLOAD_FAILED(R.string.update_err_download),

    /** Not enough room on the device for the file. */
    NO_SPACE(R.string.update_err_no_space),

    /** The bytes that arrived are not the bytes GitHub says it stored. */
    CHECKSUM(R.string.update_err_checksum),

    /** The file downloaded, but Android cannot read it as an APK. */
    NOT_AN_APK(R.string.update_err_not_apk),

    /** A valid APK, but for some other application id. */
    WRONG_PACKAGE(R.string.update_err_wrong_package),

    /**
     * Signed with a different key than the copy already installed.
     *
     * Android would refuse this install anyway, with a message nobody can act on. Catching it here
     * lets the app say what it means: this build did not come from the same place as the one you
     * are running.
     */
    WRONG_SIGNER(R.string.update_err_wrong_signer),

    /** The downloaded APK is not actually newer. Usually a release carrying the previous build. */
    NOT_NEWER(R.string.update_err_not_newer),

    /** The user has not allowed this app to install apps. */
    PERMISSION(R.string.update_err_permission),

    /** The installer opened and did not finish. */
    INSTALL_FAILED(R.string.update_err_install),

    /** The user stopped it. Not an error, but it ends the same paths. */
    CANCELLED(R.string.update_err_cancelled),
}

/** An error with whatever technical detail was available, kept apart from the sentence shown. */
data class Failure(val error: UpdateError, val detail: String? = null)
