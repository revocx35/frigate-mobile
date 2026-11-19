package io.github.revocx35.frigatemobile.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Errors with a message that can be shown to the user as is (no tokens, no stack traces). */
sealed class FrigateException(message: String) : Exception(message) {
    class BadCredentials : FrigateException("Wrong username or password.")
    class RateLimited : FrigateException("Too many sign-in attempts. Wait a minute and try again.")
    class AuthDisabled : FrigateException("This Frigate has authentication turned off.")
    class Unauthorized : FrigateException("Your session has ended. Sign in again.")
    class Forbidden : FrigateException("Your Frigate role isn't allowed to do this.")
    class NotFound(what: String) : FrigateException("$what was not found on the server.")
    class Unsupported(what: String) : FrigateException("$what needs a newer Frigate version.")
    class Http(val code: Int, message: String) : FrigateException(message)
    class Protocol(message: String) : FrigateException(message)
}

/** A short, human message for any failure (network, TLS, HTTP). */
fun Throwable.toUserMessage(): String = when (this) {
    is FrigateException -> message ?: "Error"
    is UnknownHostException -> "Server not found. Check the address."
    is SocketTimeoutException -> "The server didn't answer in time."
    is ConnectException -> "Can't connect to the server. Is it running, and is the port right?"
    is SSLPeerUnverifiedException -> "The server's certificate doesn't match its address."
    is SSLHandshakeException -> "The server's certificate isn't trusted."
    is SSLException -> "Secure connection failed: ${message ?: "TLS error"}"
    is IOException -> "Network error: ${message ?: javaClass.simpleName}"
    else -> message ?: javaClass.simpleName
}
