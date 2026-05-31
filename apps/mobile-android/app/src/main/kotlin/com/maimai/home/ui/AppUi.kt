package com.maimai.home.ui

import androidx.compose.runtime.Composable
import com.maimai.home.ui.nav.MaimaiNavHost
import com.maimai.home.ui.theme.MaimaiTheme

@Composable
fun MaimaiApp() {
    MaimaiTheme {
        MaimaiNavHost()
    }
}
