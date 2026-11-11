package com.atdevdev.distroboxer

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class DistroUi(
    val id: String,
    val name: String,
    val packageManager: String,
    val description: String,
)

private val initialDistros = listOf(
    DistroUi("debian", "Debian", "APT", "Estable y versátil"),
    DistroUi("ubuntu", "Ubuntu", "APT", "Popular y fácil de usar"),
    DistroUi("alpine", "Alpine Linux", "APK", "Ligera y minimalista"),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DistroBoxerScreen()
            }
        }
    }
}

@Composable
private fun DistroBoxerScreen() {
    val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Desconocida"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("DistroBoxer", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Tu gestor de entornos Linux",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Arquitectura del dispositivo: $deviceAbi",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Motor Rust", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Integración en desarrollo. Esta versión es una vista previa de la interfaz.")
                }
            }

            Text("Distribuciones", style = MaterialTheme.typography.titleLarge)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(initialDistros, key = { it.id }) { distro ->
                    DistroCard(distro)
                }
            }
        }
    }
}

@Composable
private fun DistroCard(distro: DistroUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(distro.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${distro.description} · ${distro.packageManager}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = {}, enabled = false) {
                Text("Próximamente")
            }
        }
    }
}
