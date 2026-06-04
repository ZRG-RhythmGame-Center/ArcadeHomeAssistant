package com.maimai.home.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

internal inline fun <reified TViewModel : ViewModel> maimaiViewModelFactory(
    crossinline createViewModel: () -> TViewModel,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return createViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
