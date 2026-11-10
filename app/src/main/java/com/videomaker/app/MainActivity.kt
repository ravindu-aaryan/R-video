package com.videomaker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.videomaker.app.ui.EditorRoute
import com.videomaker.app.ui.theme.VideoMakerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VideoMakerTheme {
                EditorRoute()
            }
        }
    }
}
