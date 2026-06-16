package com.speedevand.inkride

import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speedevand.inkride.core.design_system.InkRideTheme
import com.speedevand.inkride.navigation.AppNavigation
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import org.koin.android.ext.android.inject
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val userSettingsRepository: UserSettingsRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            val userSettings by userSettingsRepository.observeSettings()
                .collectAsStateWithLifecycle(initialValue = null)

            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val languageCode = userSettings?.languageCode ?: configuration.locales[0].language

            val localizedContext = remember(languageCode, configuration) {
                val locale = Locale.forLanguageTag(languageCode)
                Locale.setDefault(locale)
                val config = Configuration(configuration)
                config.setLocale(locale)
                val localizedConfigContext = context.createConfigurationContext(config)

                // Wrap the original activity context to preserve its nature (e.g., for starting activities)
                // but override resources/assets to provide localized content.
                object : ContextWrapper(context) {
                    override fun getResources() = localizedConfigContext.resources
                    override fun getAssets() = localizedConfigContext.assets
                }
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity
            ) {
                InkRideTheme {
                    AppNavigation()
                }
            }
        }
    }
}
