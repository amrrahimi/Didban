package com.pokervision.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pokervision.app.navigation.PokerVisionNavHost
import com.pokervision.core.ui.theme.PokerVisionTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single activity. Extends AppCompatActivity (not ComponentActivity) so that
 * AppCompatDelegate.setApplicationLocales() can switch between English and Persian on all API levels.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PokerVisionTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PokerVisionNavHost()
                }
            }
        }
    }
}
