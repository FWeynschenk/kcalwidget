package nl.flwe.kcalwidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.nav.AppNav

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface {
                    val model: MainViewModel = viewModel()
                    AppNav(model)
                }
            }
        }
    }
}
