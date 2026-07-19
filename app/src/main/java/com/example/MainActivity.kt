package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.data.DnsDatabase
import com.example.data.DnsRepository
import com.example.ui.screens.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DnsViewModel
import com.example.ui.viewmodel.DnsViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Register global crash recovery system immediately at application start
        Thread.setDefaultUncaughtExceptionHandler(com.example.util.CrashHandler(applicationContext))

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Database and Repository
        val database = DnsDatabase.getDatabase(applicationContext)
        val repository = DnsRepository(database.dnsProfileDao(), database.gamingAppDao())

        // Obtain ViewModel using Factory
        val viewModelFactory = DnsViewModelFactory(application, repository)
        val viewModel = ViewModelProvider(this, viewModelFactory)[DnsViewModel::class.java]

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
