package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import com.example.data.DnsDatabase
import com.example.data.DnsRepository
import com.example.ui.screens.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DnsViewModel
import com.example.ui.viewmodel.DnsViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Room Database, DAO and Repository
        val database = DnsDatabase.getDatabase(this)
        val dao = database.dnsProfileDao()
        val gamingAppDao = database.gamingAppDao()
        val repository = DnsRepository(dao, gamingAppDao)
        
        // Setup ViewModel
        val factory = DnsViewModelFactory(application, repository)
        val viewModel = ViewModelProvider(this, factory)[DnsViewModel::class.java]

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(dynamicColor = false) { // Disable dynamic color to enforce our premium custom neon theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF07090E) // Custom deep background slate color
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
