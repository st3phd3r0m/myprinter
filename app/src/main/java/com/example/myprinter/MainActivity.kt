package com.example.myprinter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.myprinter.navigation.AppNavigation
import com.example.myprinter.ui.theme.MyPrinterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyPrinterTheme {
                AppNavigation()
            }
        }
    }
}
