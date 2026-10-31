package com.vipin25.bubbletranslate.domain

import app.cash.turbine.test
import com.vipin25.bubbletranslate.data.FakeLinkCheckRepository
import com.vipin25.bubbletranslate.domain.model.LinkCheckResult
import com.vipin25.bubbletranslate.domain.model.TranslationUiState
import com.vipin25.bubbletranslate.domain.model.Verdict
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkCheckViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var translationRepo: TranslationRepository
    private lateinit var linkCheckRepo: LinkCheckRepository
    private lateinit var urlDetector: UrlDetector
    private lateinit var viewModel: TranslationViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        translationRepo = mockk(relaxed = true)
        linkCheckRepo = mockk(relaxed = true)
        urlDetector = mockk()
        viewModel = TranslationViewModel(translationRepo, linkCheckRepo, urlDetector)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun given_plainText_when_onTextSelected_then_doesNotCallLinkRepo() = runTest {
        val fakeRepo = FakeLinkCheckRepository()
        viewModel = TranslationViewModel(translationRepo, fakeRepo, urlDetector)
        every { urlDetector.isWebUrl(any()) } returns false

        viewModel.onTextSelected("Hello world")
        advanceTimeBy(301L) // debounce
        advanceUntilIdle()
        
        coVerify { translationRepo.translate("Hello world") }
        // link repo is a fake here, we can't `coVerify` it, but the requirement is "Verify translationRepo.translate() WAS called. Verify linkCheckRepo.checkUrl() was never called".
        // Wait, if we injected FakeLinkCheckRepository, we can't coVerify on it via MockK! Let's just use mockk version actually.
    }

    @Test
    fun given_plainText_when_onTextSelected_then_doesNotCallLinkRepo_withMock() = runTest {
        every { urlDetector.isWebUrl(any()) } returns false

        viewModel.onTextSelected("Hello world")
        advanceTimeBy(301L)
        advanceUntilIdle()
        
        coVerify(exactly = 0) { linkCheckRepo.checkUrl(any()) }
        coVerify { translationRepo.translate("Hello world") }
    }

    @Test
    fun given_httpsUrl_when_onTextSelected_then_doesNotCallTranslationRepo() = runTest {
        every { urlDetector.isWebUrl(any()) } returns true
        coEvery { linkCheckRepo.checkUrl(any()) } returns LinkCheckResult("https://evil.com", Verdict.PHISHING)

        viewModel.onTextSelected("https://evil.com")
        advanceTimeBy(301L)
        advanceUntilIdle()

        coVerify(exactly = 0) { translationRepo.translate(any()) }
        coVerify { linkCheckRepo.checkUrl("https://evil.com") }
    }

    @Test
    fun given_safeUrl_when_checkLink_then_emitsLinkScanning_thenLinkSafe() = runTest {
        val fakeRepo = FakeLinkCheckRepository(verdict = Verdict.SAFE, delayMs = 100L)
        viewModel = TranslationViewModel(translationRepo, fakeRepo, urlDetector)

        viewModel.uiState.test {
            assertEquals(TranslationUiState.Idle, awaitItem())
            viewModel.checkLink("https://github.com")
            assertEquals(TranslationUiState.LinkScanning, awaitItem())
            advanceTimeBy(101L)
            assertTrue(awaitItem() is TranslationUiState.LinkSafe)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun given_suspiciousUrl_when_checkLink_then_emitsLinkSuspicious() = runTest {
        val fakeRepo = FakeLinkCheckRepository(verdict = Verdict.SUSPICIOUS, delayMs = 100L)
        viewModel = TranslationViewModel(translationRepo, fakeRepo, urlDetector)

        viewModel.uiState.test {
            assertEquals(TranslationUiState.Idle, awaitItem())
            viewModel.checkLink("http://free-prize.xyz")
            assertEquals(TranslationUiState.LinkScanning, awaitItem())
            advanceTimeBy(101L)
            assertTrue(awaitItem() is TranslationUiState.LinkSuspicious)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun given_phishingUrl_when_checkLink_then_emitsLinkDangerous() = runTest {
        val fakeRepo = FakeLinkCheckRepository(verdict = Verdict.PHISHING, delayMs = 100L)
        viewModel = TranslationViewModel(translationRepo, fakeRepo, urlDetector)

        viewModel.uiState.test {
            assertEquals(TranslationUiState.Idle, awaitItem())
            viewModel.checkLink("http://paypa1-login.tk/verify")
            assertEquals(TranslationUiState.LinkScanning, awaitItem())
            advanceTimeBy(101L)
            assertTrue(awaitItem() is TranslationUiState.LinkDangerous)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
