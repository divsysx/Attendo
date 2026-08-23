package com.attendo.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.attendo.AppContainer
import com.attendo.AttendoApplication

/**
 * The application's [AppContainer], reached from inside a `viewModelFactory` initializer.
 *
 * This is the whole of the app's dependency injection: `ViewModelProvider` puts the
 * `Application` into [CreationExtras], and every ViewModel's `Factory` pulls its
 * repositories out of it. No framework, no generated code, and the ViewModels themselves
 * still take plain constructor parameters, so they remain testable without Android.
 */
internal val CreationExtras.container: AppContainer
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AttendoApplication)
        .container
