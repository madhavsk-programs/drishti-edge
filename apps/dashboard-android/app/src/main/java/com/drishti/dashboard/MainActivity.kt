package com.drishti.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drishti.dashboard.ui.DashboardApp
import com.drishti.dashboard.ui.DashboardViewModel
import com.drishti.dashboard.ui.RepositoryHolder
import com.drishti.dashboard.ui.theme.DrishtiDashboardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The whole app: one activity, one view model, three screens.
 *
 * There is no dependency-injection framework and no navigation library. With a
 * single data source and a sealed route type, both would be ceremony around
 * about twenty lines of wiring — and the wiring that matters, the seam where a
 * real desk feed replaces the demo one, is one line below.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Deliberately not lifecycleScope: the repository's clock should keep
        // running across a rotation so acknowledgements and the ticking feed
        // survive it. It lives as long as the process.
        RepositoryHolder.installDemo(CoroutineScope(SupervisorJob() + Dispatchers.Default))

        setContent {
            DrishtiDashboardTheme {
                val viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory)
                DashboardApp(viewModel)
            }
        }
    }
}
