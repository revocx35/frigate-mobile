package io.github.revocx35.frigatemobile

import android.util.Base64
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.revocx35.frigatemobile.data.AppGraph
import io.github.revocx35.frigatemobile.data.SecretCipher

/** Robolectric has no Android Keystore: "encrypt" by base64 so the storage code paths still run. */
class FakeCipher : SecretCipher {
    override fun encrypt(plain: String): String = "fake:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
    override fun decrypt(sealed: String): String? =
        if (sealed.startsWith("fake:")) String(Base64.decode(sealed.removePrefix("fake:"), Base64.NO_WRAP)) else null
}

class TestApp : FrigateMobileApp() {
    override fun createGraph(): AppGraph {
        WorkManagerTestInitHelper.initializeTestWorkManager(this, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        return AppGraph(this, FakeCipher())
    }
}

@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.waitForText(text: String, timeoutMs: Long = 20_000) =
    waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMs)

@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.waitForTag(tag: String, timeoutMs: Long = 20_000) =
    waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMs)

fun ComposeContentTestRule.signIn(server: String, user: String, password: String) {
    waitForTag("server")
    onNodeWithTag("server").performTextClearance()
    onNodeWithTag("server").performTextInput(server)
    onNodeWithTag("user").performTextClearance()
    onNodeWithTag("user").performTextInput(user)
    onNodeWithTag("password").performTextInput(password)
    onNodeWithTag("sign_in").performClick()
}
