package com.jobscout.app.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.jobscout.core.database.*
import com.jobscout.core.background.WorkScheduler
import com.jobscout.domain.*
import com.jobscout.feature.resume.data.LocalPdfImporter
import com.jobscout.feature.resume.data.LocalResumeExporter
import com.jobscout.feature.search.data.*
import dagger.*
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Dns
import java.net.UnknownHostException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun htmlCache(@ApplicationContext context: Context): HtmlPageCache = HtmlPageCache(java.io.File(context.cacheDir, "public-job-pages"))
    @Provides @Singleton fun gson(): Gson = Gson()
    @Provides @Singleton fun database(@ApplicationContext context: Context): ScoutDatabase =
        Room.databaseBuilder(context, ScoutDatabase::class.java, "jobscout.db").build()
    @Provides fun dao(database: ScoutDatabase): JobDao = database.jobs()
    @Provides @Singleton fun http(): OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> =
            Dns.SYSTEM.lookup(hostname).filter { address ->
                !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                    !address.isMulticastAddress && !(address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
            }.ifEmpty { throw UnknownHostException("Apenas endereços públicos são aceitos.") }
        })
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
            .header("User-Agent", "JobScoutCommunity/0.14.0 (Android personal job discovery)")
            .apply { if (chain.request().header("Accept") == null) header("Accept", "application/json") }.build()) }.build()
    private fun retrofit(url: String, client: OkHttpClient, gson: Gson): Retrofit = Retrofit.Builder()
        .baseUrl(url).client(client).addConverterFactory(GsonConverterFactory.create(gson)).build()
    @Provides fun greenhouse(client: OkHttpClient, gson: Gson): GreenhouseApi =
        retrofit("https://boards-api.greenhouse.io/", client, gson).create(GreenhouseApi::class.java)
    @Provides fun lever(client: OkHttpClient, gson: Gson): LeverApi =
        retrofit("https://api.lever.co/", client, gson).create(LeverApi::class.java)
    @Provides fun freehire(client: OkHttpClient, gson: Gson): FreehireApi =
        retrofit("https://freehire.me/", client, gson).create(FreehireApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class Bindings {
    @Binds abstract fun freehireCredentials(impl: ApiCredentialStore): FreehireTokenProvider
    @Binds abstract fun credentialSettings(impl: ApiCredentialStore): FreehireCredentialSettings
    @Binds abstract fun pageVerifier(impl: PublicJobPageVerifier): JobPageVerifier
    @Binds @IntoSet abstract fun dorks(impl: DorksSource): JobSource
    @Binds @IntoSet abstract fun htmlCrawler(impl: HtmlCrawlerSource): JobSource
    @Binds abstract fun scheduler(impl: WorkScheduler): BackgroundScheduler
    @Binds @Singleton abstract fun repository(impl: LocalScoutRepository): ScoutRepository
    @Binds abstract fun importer(impl: LocalPdfImporter): ResumeImporter
    @Binds abstract fun resumeExporter(impl: LocalResumeExporter): ResumeExporter
    @Binds abstract fun webImporter(impl: LocalWebJobImporter): WebJobImporter
    @Binds @IntoSet abstract fun greenhouse(impl: GreenhouseSource): JobSource
    @Binds @IntoSet abstract fun lever(impl: LeverSource): JobSource
    @Binds @IntoSet abstract fun freehire(impl: FreehireSource): JobSource
    @Binds @IntoSet abstract fun rss(impl: RssSource): JobSource
}
