package org.familycircle.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.familycircle.android.api.QrLink

/**
 * Memory-only hand-over of codes between screens: codes from QR codes and app links are never
 * persisted or logged (docs/QR_FORMAT.md). Taking a value clears it.
 */
class OnboardingHandoff {
    @Volatile private var inviteCode: String? = null

    /** Invite code from a scanned QR code, for the registration form. */
    fun offerInviteCode(code: String) {
        inviteCode = code
    }

    fun takeInviteCode(): String? = inviteCode.also { inviteCode = null }

    private val _link = MutableStateFlow<QrLink?>(null)

    /** Invite/pairing link opened from outside the app (browser, WhatsApp …), waiting for a screen. */
    val link: StateFlow<QrLink?> = _link.asStateFlow()

    fun offerLink(link: QrLink) {
        _link.value = link
    }

    /** Takes [expected] if it is still the pending link (a newer link is never lost). */
    fun takeLink(expected: QrLink): Boolean = _link.compareAndSet(expected, null)

    fun clear() {
        inviteCode = null
        _link.value = null
    }
}
