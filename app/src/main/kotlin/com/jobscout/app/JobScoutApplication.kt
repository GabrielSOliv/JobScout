package com.jobscout.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class JobScoutApplication : Application(), Configuration.Provider, coil.ImageLoaderFactory {
    @Inject lateinit var http: okhttp3.OkHttpClient
    override fun newImageLoader() = coil.ImageLoader.Builder(this).okHttpClient(http).build()
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
    override fun onCreate() { super.onCreate(); PDFBoxResourceLoader.init(this) }
}
