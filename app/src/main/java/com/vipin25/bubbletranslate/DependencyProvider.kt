package com.vipin25.bubbletranslate

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.vipin25.bubbletranslate.data.OpenRouterTranslationRepository
import com.vipin25.bubbletranslate.data.PrefsDataStore
import com.vipin25.bubbletranslate.data.TranslationCache
import com.vipin25.bubbletranslate.domain.TranslationViewModel

object GlobalViewModelStoreOwner : ViewModelStoreOwner {
    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store
}

object DependencyProvider {
    private var prefsDataStore: PrefsDataStore? = null

    fun getFactory(context: Context): ViewModelProvider.Factory {
        if (prefsDataStore == null) {
            prefsDataStore = PrefsDataStore(context.applicationContext)
        }
        TranslationCache.init(context.applicationContext)
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TranslationViewModel(
                    repository = OpenRouterTranslationRepository(prefsDataStore!!),
                    prefsDataStore = prefsDataStore!!
                ) as T
            }
        }
    }
    
    fun viewModel(context: Context): TranslationViewModel {
        return ViewModelProvider(GlobalViewModelStoreOwner, getFactory(context))[TranslationViewModel::class.java]
    }
}
