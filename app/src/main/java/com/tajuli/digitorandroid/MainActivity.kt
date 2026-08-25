package com.tajuli.digitorandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tajuli.digitorandroid.ui.editor.DigitorEditorScreenV7
import com.tajuli.digitorandroid.ui.theme.DigitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DigitorTheme {
                DigitorEditorScreenV7()
            }
        }
    }
}
