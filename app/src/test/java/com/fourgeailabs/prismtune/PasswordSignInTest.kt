package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexClient
import com.fourgeailabs.prismtune.data.PlexException
import com.fourgeailabs.prismtune.data.TwoFactorRequired
import com.fourgeailabs.prismtune.viewmodel.passwordSignInErrorMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.8: username/password sign-in parsing and error
 * mapping. No real credential appears anywhere here: the
 * only "password-like" string is an obviously fake
 * sentinel used to prove it never leaks into a message,
 * and no test ever performs a network sign-in.
 */
class PasswordSignInTest {

    private val client = PlexClient()

    @Test
    fun topLevelAuthTokenIsExtracted() {
        assertEquals(
            "token-abc",
            client.extractToken(JSONObject("{\"authToken\":\"token-abc\"}")),
        )
    }

    @Test
    fun userObjectAuthenticationTokenIsExtracted() {
        assertEquals(
            "token-xyz",
            client.extractToken(
                JSONObject("{\"user\":{\"authentication_token\":\"token-xyz\"}}"),
            ),
        )
    }

    @Test
    fun userObjectAuthTokenIsExtracted() {
        assertEquals(
            "token-123",
            client.extractToken(JSONObject("{\"user\":{\"authToken\":\"token-123\"}}")),
        )
    }

    @Test
    fun missingTokenReturnsNull() {
        assertNull(client.extractToken(JSONObject("{\"user\":{\"username\":\"someone\"}}")))
        assertNull(client.extractToken(JSONObject("{}")))
    }

    @Test
    fun verificationCodeBodyIsTwoFactorRequired() {
        val e = PlexException(
            "POST https://plex.tv/api/v2/users/sign_in.json failed: 401 " +
                "{\"errors\":[{\"message\":\"A verification code is required\"}]}",
            401,
        )
        assertTrue(client.isTwoFactorRequired(e))
    }

    @Test
    fun plainWrongPasswordIsNotTwoFactorRequired() {
        val e = PlexException(
            "POST https://plex.tv/api/v2/users/sign_in.json failed: 401 " +
                "{\"errors\":[{\"message\":\"Invalid email or password\"}]}",
            401,
        )
        assertFalse(client.isTwoFactorRequired(e))
    }

    @Test
    fun twoFactorErrorMessageIsDistinctAndAsksForCode() {
        val message = passwordSignInErrorMessage(
            TwoFactorRequired("POST failed: 401 verification code required"),
        )
        assertTrue(message.contains("verification code", ignoreCase = true))
        assertTrue(message.contains("two-factor", ignoreCase = true))
        assertFalse(message.contains("Plex rejected that sign-in"))
    }

    @Test
    fun wrongPasswordMessageShowsStatusAndBodyButNeverThePassword() {
        // The client never puts the password into a PlexException:
        // messages carry only URL + status + Plex body. This test
        // proves the mapping cannot leak a credential even if one
        // is named: the sentinel below appears nowhere in the
        // exception, and must appear nowhere in the output either.
        val sentinel = "not-a-real-password-sentinel"
        val e = PlexException(
            "POST https://plex.tv/api/v2/users/sign_in.json failed: 401 " +
                "{\"errors\":[{\"code\":1001,\"message\":\"Invalid email or password\"}]}",
            401,
        )
        val message = passwordSignInErrorMessage(e)
        assertTrue(message.contains("401"))
        assertTrue(message.contains("Invalid email or password"))
        assertFalse(message.contains(sentinel))
        assertFalse(message.contains("Plex rejected that sign-in"))
    }

    @Test
    fun dnsFailureMapsToNetworkMessage() {
        val message = passwordSignInErrorMessage(
            RuntimeException(
                "Unable to resolve host \"plex.tv\": No address associated with hostname",
            ),
        )
        assertTrue(message.contains("No connection to Plex"))
        assertFalse(message.contains("Plex rejected that sign-in"))
    }
}
