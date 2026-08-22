package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)

/** Data source and privacy sub-screen: the how-Vela-handles-data explainer + privacy policy link. */
@Composable
internal fun DataPrivacySettingsScreen(vm: app.vela.ui.map.MapViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_data_privacy), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_data_privacy_hint))
        SettingsGroup {
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        FilledTonalButton(
            // The top (and only) focusable control; on the old page this button sat beside a
            // VelaSwitch whose ring token satisfied the audit window - here it carries its own ring.
            modifier = topRow.dpadHighlight(androidx.compose.material3.ButtonDefaults.filledTonalShape),
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/kaiser-app/Vela/blob/main/PRIVACY.md"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ) { Text(stringResource(R.string.settings_privacy_button)) }
        }
        }
        // Live rechecks: the ~2-min traffic/route recheck during nav (a Google request each time).
        Spacer(Modifier.height(8.dp))
        var liveRechecks by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(vm.liveRechecksOn()) }
        SettingsGroup {
        app.vela.ui.settings.ToggleRow(
            label = stringResource(R.string.settings_live_rechecks),
            checked = liveRechecks,
            onCheckedChange = { on ->
                liveRechecks = on
                vm.setLiveRechecks(on)
            },
            hint = stringResource(R.string.settings_live_rechecks_hint),
        )
        }
        Spacer(Modifier.height(24.dp))
        
        var aiKey by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
                    .getString("gemini_api_key", "") ?: ""
            )
        }
        PageIntro("NA-VIGATOR AI")
        SettingsGroup {
            androidx.compose.material3.OutlinedTextField(
                value = aiKey,
                onValueChange = {
                    aiKey = it
                    context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
                        .edit().putString("gemini_api_key", it).apply()
                },
                label = { Text("Gemini API kulcs") },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
        }
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "A Gemini API kulcsot a Google AI Studio-ban igényelhetsz ingyenesen. Az AI segít megválaszolni a helyszínekkel kapcsolatos kérdéseidet.",
                style = MaterialTheme.typography.bodySmall,
                color = if (app.vela.ui.theme.isAppInDarkTheme()) app.vela.ui.SheetPalette.DimDark else app.vela.ui.SheetPalette.DimLight
            )
        }
        Spacer(Modifier.height(16.dp))

        // Fallback provider (2026-08-21, "az AI bekötés eléggé korlátoz"): Gemini's free tier is
        // very tight (as low as 5 requests/day on some models). OpenRouter — optional — is tried
        // automatically if Gemini fails or isn't configured. Same chain-of-fallback shape as the
        // HERE/TomTom traffic overlay: nothing here breaks if this field is left empty, it just
        // means there's no second provider to fall back to.
        var openRouterKey by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
                    .getString("openrouter_api_key", "") ?: ""
            )
        }
        SettingsGroup {
            androidx.compose.material3.OutlinedTextField(
                value = openRouterKey,
                onValueChange = {
                    openRouterKey = it
                    context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
                        .edit().putString("openrouter_api_key", it).apply()
                },
                label = { Text("OpenRouter API kulcs (tartalék)") },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
        }
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Opcionális. Ha a Gemini elfogyott vagy nem elérhető, a NA-VIGATOR automatikusan az OpenRouter ingyenes modelljére vált át — nincs teendőd, csak add meg a kulcsot. Kulcsot az openrouter.ai oldalon igényelhetsz.",
                style = MaterialTheme.typography.bodySmall,
                color = if (app.vela.ui.theme.isAppInDarkTheme()) app.vela.ui.SheetPalette.DimDark else app.vela.ui.SheetPalette.DimLight
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
