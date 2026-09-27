package com.mnnkit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.mnnkit.app.ui.AppRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        container = (application as MnnKitApplication).container

        // 启动时刷新一次模型目录（失败会自动回退到内置精选列表，不影响进入应用）
        lifecycleScope.launch {
            container.modelManager.refresh()
        }

        setContent {
            AppRoot(container = container)
        }
    }
}
