package com.batterytracker.ui

import androidx.lifecycle.*
import com.batterytracker.data.db.BatterySession
import com.batterytracker.data.repository.BatteryRepository

class MainViewModel(private val repository: BatteryRepository) : ViewModel() {

    /** All sessions (ongoing and completed) observed as LiveData by MainActivity. */
    val sessions: LiveData<List<BatterySession>> = repository.allSessions

    /** Get direct snapshot of sessions for export. */
    suspend fun getAllSessionsForExport(): List<BatterySession> =
        repository.getAllSessionsList()
}

/** Factory so we can pass the repository into the ViewModel constructor. */
class MainViewModelFactory(private val repository: BatteryRepository) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(repository) as T
    }
}
