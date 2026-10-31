package com.vipin25.bubbletranslate.domain

import app.cash.turbine.test
import com.vipin25.bubbletranslate.data.FakeLinkCheckRepository
import com.vipin25.bubbletranslate.data.FakeTranslationRepository
import com.vipin25.bubbletranslate.domain.model.TranslationUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialStateIsIdle() = runTest {
        val repository = FakeTranslationRepository(modelDownloaded = true)
        val viewModel = TranslationViewModel(repository, FakeLinkCheckRepository())

        viewModel.uiState.test {
            assertEquals(TranslationUiState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testLoadingToSuccess() = runTest {
        val repository = FakeTranslationRepository(modelDownloaded = true)
        val viewModel = TranslationViewModel(repository, FakeLinkCheckRepository())

        viewModel.uiState.test {
            assertEquals(TranslationUiState.Idle, awaitItem())
            viewModel.onTextSelected("hello")
            
            assertEquals(TranslationUiState.Loading, awaitItem())
            
            val success = awaitItem() as TranslationUiState.Success
            assertEquals("Translated: hello", success.translation)
            
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testLoadingToError() = runTest {
        val repository = FakeTranslationRepository(modelDownloaded = true, shouldFail = true)
        val viewModel = TranslationViewModel(repository, FakeLinkCheckRepository())

        viewModel.uiState.test {
            assertEquals(TranslationUiState.Idle, awaitItem())
            viewModel.onTextSelected("hello")
            
            assertEquals(TranslationUiState.Loading, awaitItem())
            
            val error = awaitItem() as TranslationUiState.Error
            assertTrue(error.message.isNotEmpty())
            
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testModelDownloadingState() = runTest {
        val repository = FakeTranslationRepository(modelDownloaded = false)
        val viewModel = TranslationViewModel(repository, FakeLinkCheckRepository())

        viewModel.uiState.test {
            assertEquals(TranslationUiState.Idle, awaitItem())
            viewModel.onTextSelected("hello")
            
            assertEquals(TranslationUiState.ModelDownloading, awaitItem())
            
            val success = awaitItem() as TranslationUiState.Success
            assertEquals("Translated: hello", success.translation)
            
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testDismissToIdle() = runTest {
        val repository = FakeTranslationRepository(modelDownloaded = true)
        val viewModel = TranslationViewModel(repository, FakeLinkCheckRepository())

        viewModel.uiState.test {
            assertEquals(TranslationUiState.Idle, awaitItem())
            viewModel.onTextSelected("hello")
            
            assertEquals(TranslationUiState.Loading, awaitItem())
            
            viewModel.dismiss()
            assertEquals(TranslationUiState.Idle, awaitItem())
            
            cancelAndIgnoreRemainingEvents()
        }
    }
}
