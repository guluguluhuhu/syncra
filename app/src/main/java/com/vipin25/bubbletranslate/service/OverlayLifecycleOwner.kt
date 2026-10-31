package com.vipin25.bubbletranslate.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.vipin25.bubbletranslate.GlobalViewModelStoreOwner

class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, DefaultLifecycleObserver {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = GlobalViewModelStoreOwner.viewModelStore

    fun init() {
        savedStateRegistryController.performRestore(null)
        handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        handleLifecycleEvent(Lifecycle.Event.ON_START)
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        // Store NOT cleared so view models survive overlay close and can be retrieved later.
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    override fun onCreate(owner: LifecycleOwner) {
        handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStart(owner: LifecycleOwner) {
        handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume(owner: LifecycleOwner) {
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause(owner: LifecycleOwner) {
        handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onStop(owner: LifecycleOwner) {
        handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
