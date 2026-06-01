package com.maimai.home.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.maimai.home.ui.nav.MaimaiNavHost
import com.maimai.home.ui.theme.MaimaiTheme

@Composable
fun MaimaiApp() {
    MaimaiTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MaimaiNavHost()
        }
    }
}
