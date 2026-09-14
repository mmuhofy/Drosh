package com.iris.irisshell.data.di

import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Qualifier

/**
 * Qualifiers for application-wide scope objects.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
