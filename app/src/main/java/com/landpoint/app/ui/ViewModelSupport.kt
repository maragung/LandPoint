package com.landpoint.app.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import com.landpoint.app.LandPointApp

/** Resolves the app container from within a ViewModel factory. */
fun CreationExtras.container() =
    (this[APPLICATION_KEY] as LandPointApp).container
