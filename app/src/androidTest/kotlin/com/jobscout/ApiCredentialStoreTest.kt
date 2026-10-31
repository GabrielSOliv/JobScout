package com.jobscout

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.jobscout.core.database.ApiCredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ApiCredentialStoreTest {
    @Test fun credentialIsEncryptedOnDiskAndCanBeDeleted() = runBlocking {
        // Run only in a disposable emulator: this tests the public application's storage.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ApiCredentialStore(context)
        val token = "fixture-local-token"
        try {
            store.save(token)
            val disk = context.getSharedPreferences("api_credentials", Context.MODE_PRIVATE).getString("freehire", null)
            assertNotNull(disk); assertFalse(disk!!.contains(token))
            assertEquals(token, store.token())
            store.clear(); assertNull(store.token()); assertFalse(store.hasStoredToken())
        } finally { store.clear() }
    }
}
