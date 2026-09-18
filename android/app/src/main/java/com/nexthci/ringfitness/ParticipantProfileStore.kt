package com.nexthci.ringfitness

import android.content.Context
import java.util.UUID

data class ParticipantProfile(
    val participantId: String,
    val displayName: String,
    val uploadLink: String,
    val authorizationBaseUrl: String? = null,
    val enrollmentCode: String? = null,
)

/** Stores only the current username and this app installation's stable identifier. */
class ParticipantProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val installationId: String
        get() {
            prefs.getString(KEY_INSTALLATION_ID, null)?.let { return it }
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALLATION_ID, created).commit()
            return created
        }

    fun get(): ParticipantProfile? {
        val participantId = prefs.getString(KEY_PARTICIPANT_ID, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val uploadLink = prefs.getString(KEY_UPLOAD_LINK, null) ?: return null
        return ParticipantProfile(
            participantId = participantId,
            displayName = username,
            uploadLink = uploadLink,
            authorizationBaseUrl = prefs.getString(KEY_AUTHORIZATION_BASE_URL, null),
            enrollmentCode = prefs.getString(KEY_ENROLLMENT_CODE, null),
        )
    }

    fun save(profile: ParticipantProfile) {
        require(isValidUsername(profile.displayName)) { USERNAME_RULE }
        prefs.edit()
            .putString(KEY_PARTICIPANT_ID, profile.participantId)
            .putString(KEY_USERNAME, normalizeUsername(profile.displayName))
            .putString(KEY_UPLOAD_LINK, normalizeUploadLink(profile.uploadLink))
            .putString(KEY_AUTHORIZATION_BASE_URL, profile.authorizationBaseUrl?.trim()?.trimEnd('/'))
            .putString(KEY_ENROLLMENT_CODE, profile.enrollmentCode)
            .apply()
        installationId
    }

    fun clearCurrentUser() {
        prefs.edit()
            .remove(KEY_PARTICIPANT_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_UPLOAD_LINK)
            .remove(KEY_AUTHORIZATION_BASE_URL)
            .remove(KEY_ENROLLMENT_CODE)
            .apply()
    }

    fun isCurrentBuildConfirmed(): Boolean =
        prefs.getLong(KEY_CONFIRMED_VERSION, -1L) == BuildConfig.VERSION_CODE.toLong()

    fun markCurrentBuildConfirmed() {
        prefs.edit().putLong(KEY_CONFIRMED_VERSION, BuildConfig.VERSION_CODE.toLong()).apply()
    }

    fun createProfile(username: String, participantId: String): ParticipantProfile = ParticipantProfile(
        participantId = participantId,
        displayName = normalizeUsername(username),
        uploadLink = BuildConfig.STUDY_UPLOAD_LINK,
        authorizationBaseUrl = BuildConfig.STUDY_AUTHORIZATION_URL.takeIf(String::isNotBlank),
        enrollmentCode = BuildConfig.STUDY_ENROLLMENT_CODE.takeIf(String::isNotBlank),
    )

    companion object {
        const val USERNAME_RULE = "用户名需为 3–24 位字母或数字"
        private const val PREFS_NAME = "ringfitness_identity"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_PARTICIPANT_ID = "participant_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_UPLOAD_LINK = "upload_link"
        private const val KEY_AUTHORIZATION_BASE_URL = "authorization_base_url"
        private const val KEY_ENROLLMENT_CODE = "enrollment_code"
        private const val KEY_CONFIRMED_VERSION = "confirmed_version_code"
        private val usernamePattern = Regex("^[A-Za-z0-9]{3,24}$")

        fun isValidUsername(value: String): Boolean = usernamePattern.matches(value.trim())
        fun normalizeUsername(value: String): String = value.trim().lowercase()
        fun normalizeUploadLink(value: String): String = value.trim().let {
            if (it.endsWith('/')) it else "$it/"
        }
    }
}
