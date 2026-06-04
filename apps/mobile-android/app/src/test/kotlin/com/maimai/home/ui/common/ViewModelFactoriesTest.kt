package com.maimai.home.ui.common

import androidx.lifecycle.ViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ViewModelFactoriesTest {

    @Test
    fun maimaiViewModelFactory_createsExpectedViewModel() {
        val factory = maimaiViewModelFactory { ExpectedViewModel() }

        val viewModel = factory.create(ExpectedViewModel::class.java)

        assertThat(viewModel).isInstanceOf(ExpectedViewModel::class.java)
    }

    @Test
    fun maimaiViewModelFactory_rejectsUnexpectedViewModelClass() {
        val factory = maimaiViewModelFactory { ExpectedViewModel() }

        assertThrows(IllegalArgumentException::class.java) {
            factory.create(OtherViewModel::class.java)
        }
    }

    private class ExpectedViewModel : ViewModel()

    private class OtherViewModel : ViewModel()
}
