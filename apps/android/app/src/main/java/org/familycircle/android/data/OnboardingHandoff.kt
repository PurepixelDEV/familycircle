package org.familycircle.android.data

/**
 * Hands a scanned invite code from the server screen to the registration form. Memory only: codes
 * from QR codes are never persisted or logged (docs/QR_FORMAT.md). Taking the code clears it.
 */
class OnboardingHandoff {
    @Volatile private var inviteCode: String? = null

    fun offerInviteCode(code: String) {
        inviteCode = code
    }

    fun takeInviteCode(): String? = inviteCode.also { inviteCode = null }

    fun clear() {
        inviteCode = null
    }
}
