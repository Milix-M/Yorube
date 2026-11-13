package com.hermes.client.testutil

import com.hermes.client.HermesApp
import java.security.Security

/** Robolectric has no Android Keystore service; use an in-memory JCA provider in JVM tests. */
class CryptoTestApplication : HermesApp() {
    override fun onCreate() {
        Security.addProvider(TestAndroidKeyStore())
        super.onCreate()
    }
}
