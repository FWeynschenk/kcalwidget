package nl.flwe.kcalwidget.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.flwe.kcalwidget.data.settings.HealthMetric
import nl.flwe.kcalwidget.data.sources.SourceDiscovery
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.components.ChoiceRow
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.SectionCard

/**
 * Lets each calculation be pointed at a specific writing app.
 *
 * The default everywhere is "all apps", which is right until two apps write the same thing
 * — Health Connect sums overlapping records, so two trackers both logging a walk are
 * counted twice. Those metrics carry a recommendation towards a single writer.
 */
@Composable
fun SourcesScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.discoverSources() }

    SettingsScaffold("Data sources", onBack) {
        item {
            SectionCard("Detected writers") {
                Explainer(
                    "Apps that have written each type in the last two weeks, with how many " +
                        "days each contributed. An app that wrote once a month ago is " +
                        "probably not your main source."
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.discoverSources(forceRefresh = true) },
                    enabled = !state.discoveringSources,
                ) { Text(if (state.discoveringSources) "Scanning…" else "Rescan") }
            }
        }

        HealthMetric.entries.forEach { metric ->
            item(key = metric.name) {
                val options = state.sourceCatalog?.optionsFor(metric).orEmpty()
                val selected = state.settings.sources.packagesFor(metric)

                SectionCard(SourceDiscovery.displayName(metric)) {
                    Explainer(SourceDiscovery.explanation(metric))
                    Spacer(Modifier.height(8.dp))

                    if (options.isEmpty()) {
                        Text(
                            text = when {
                                state.discoveringSources -> "Looking…"
                                // A null catalog means the scan gave up, which is not the
                                // same as finding nothing.
                                state.sourceCatalog == null -> "The scan timed out. Try Rescan."
                                else -> "Nothing has written this in the last two weeks."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        return@SectionCard
                    }

                    val allRecommended = metric !in SourceDiscovery.SINGLE_WRITER_METRICS ||
                        options.size == 1
                    ChoiceRow(
                        label = "All apps" + if (allRecommended) "  (recommended)" else "",
                        selected = selected.isEmpty(),
                    ) {
                        viewModel.updateSettings {
                            it.copy(sources = it.sources.with(metric, emptySet()))
                        }
                    }

                    options.forEach { option ->
                        val suffix = buildString {
                            append("  ${option.daysWithData} days")
                            if (option.recommended) append("  (recommended)")
                        }
                        ChoiceRow(
                            label = option.label + suffix,
                            selected = selected == setOf(option.packageName),
                        ) {
                            viewModel.updateSettings {
                                it.copy(
                                    sources = it.sources.with(metric, setOf(option.packageName))
                                )
                            }
                        }
                    }

                    if (metric in SourceDiscovery.SINGLE_WRITER_METRICS && options.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        Explainer(
                            "Two apps write this. Leaving it on \"all apps\" adds them " +
                                "together, which inflates your burn."
                        )
                    }
                }
            }
        }
    }
}
