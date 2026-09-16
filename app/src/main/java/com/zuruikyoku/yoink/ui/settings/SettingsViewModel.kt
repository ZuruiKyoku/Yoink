package com.zuruikyoku.yoink.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zuruikyoku.yoink.data.settings.AppSettings
import com.zuruikyoku.yoink.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    val settings: StateFlow<AppSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setImageFolder(name: String) {
        viewModelScope.launch { repository.setImageFolder(name) }
    }

    fun setVideoFolder(name: String) {
        viewModelScope.launch { repository.setVideoFolder(name) }
    }

    fun setPerPlatformSubfolders(enabled: Boolean) {
        viewModelScope.launch { repository.setPerPlatformSubfolders(enabled) }
    }

    fun setAutoClipboardDetect(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoClipboardDetect(enabled) }
    }
}
