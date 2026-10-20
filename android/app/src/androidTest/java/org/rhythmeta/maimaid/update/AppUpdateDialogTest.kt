package org.rhythmeta.maimaid.update

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.rhythmeta.maimaid.BuildConfig
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.update.*
import org.rhythmeta.maimaid.ui.theme.AppThemeSettings
import org.rhythmeta.maimaid.ui.theme.ColorMode
import org.rhythmeta.maimaid.ui.theme.MaimaidTheme
import org.rhythmeta.maimaid.ui.update.AppUpdateDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File

/** Device layout checks cover scrolling and popup hosting that JVM tests cannot exercise. */
class AppUpdateDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localizedDialogsKeepActionsVisibleInLightAndDarkThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = mutableStateOf(Configuration(context.resources.configuration))
        val dark = mutableStateOf(false)
        val localeTag = mutableStateOf("zh-CN")
        var downloads = 0
        var sharedLinks = 0
        compose.setContent {
            key(localeTag.value, dark.value) {
                val localizedContext = context.createConfigurationContext(configuration.value)
                CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides configuration.value) {
                    MaimaidTheme(settings = AppThemeSettings(colorMode = if (dark.value) ColorMode.DARK else ColorMode.LIGHT)) {
                        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                            Box(Modifier.fillMaxSize()) {
                                AppUpdateDialog(true, fixture(), true, {}, { sharedLinks++ }, { downloads++ }, {}, {}, {}, {})
                            }
                        }
                    }
                }
            }
        }
        for (tag in listOf("zh-CN", "zh-TW", "en", "ja")) {
            for (darkMode in listOf(false, true)) {
                compose.runOnIdle {
                    configuration.value = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
                    localeTag.value = tag
                    dark.value = darkMode
                }
                compose.waitForIdle()
                val resources = context.createConfigurationContext(configuration.value).resources
                compose.onNodeWithText(resources.getString(R.string.update_found)).assertIsDisplayed()
                compose.onNodeWithText("${BuildConfig.VERSION_CODE} → ${BuildConfig.VERSION_CODE + 2}").assertIsDisplayed()
                compose.onNodeWithText(resources.getString(R.string.update_share)).assertIsDisplayed()
                compose.onNodeWithText(resources.getString(R.string.update_download)).assertIsDisplayed()
                compose.onNodeWithText(resources.getString(R.string.update_later)).assertDoesNotExist()
                compose.onNodeWithText(resources.getString(R.string.update_share)).performClick()
                compose.onNodeWithText(resources.getString(R.string.update_download)).performClick()
                compose.onNodeWithText("→", substring = true).performScrollTo()
                val screenshot = File(context.getExternalFilesDir(null), "update-$tag-${if (darkMode) "dark" else "light"}.png")
                screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                compose.onNodeWithText(resources.getString(R.string.update_view_changes)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(resources.getString(R.string.update_download)).assertIsDisplayed()
            }
        }
        compose.runOnIdle {
            assertEquals(8, downloads)
            assertEquals(8, sharedLinks)
        }
    }

    private fun fixture(): AppUpdateState {
        val sha = "b".repeat(40)
        val artifact = AppUpdateArtifact("arm64-v8a", "https://assets.rhythmeta.org/android/ci/builds/1-1/maimaid-arm64-v8a.apk", 12345, "a".repeat(64))
        val manifest = AppUpdateManifest(1, "org.rhythmeta.maimaid", "1.2.8", BuildConfig.VERSION_CODE.toLong() + 2, 29, "main", sha, "Rhythmeta", "2026-10-20T10:00:00Z", "https://assets.rhythmeta.org/android/ci/builds/1-1/history.json", listOf(artifact))
        return AppUpdateState(AppUpdatePhase.Available, manifest, artifact,
            AppUpdateChanges((1..20).map { AppUpdateCommit(sha, emptyList(), "feat(android): 改进应用内更新下载流程与界面 — validate long commit messages and readable wrapping #$it", "Rhythmeta") }, true))
    }
}
