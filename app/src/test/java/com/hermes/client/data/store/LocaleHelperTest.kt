package com.hermes.client.data.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LocaleHelperTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @After
    fun restoreLocale() {
        java.util.Locale.setDefault(java.util.Locale.ENGLISH)
    }

    @Test
    fun localeFor_allCodes() {
        assertEquals(java.util.Locale.JAPANESE, LocaleHelper.localeFor(LANGUAGE_JA))
        assertEquals(java.util.Locale.ENGLISH, LocaleHelper.localeFor(LANGUAGE_EN))
        assertEquals(java.util.Locale.SIMPLIFIED_CHINESE, LocaleHelper.localeFor(LANGUAGE_ZH))
        assertEquals(java.util.Locale.KOREAN, LocaleHelper.localeFor(LANGUAGE_KO))
        assertNull(LocaleHelper.localeFor(LANGUAGE_SYSTEM))
        assertNull(LocaleHelper.localeFor("xx"))
    }

    @Test
    fun contextualize_systemReturnsBase() {
        val base = app()
        assertSame(base, LocaleHelper.contextualize(base, LANGUAGE_SYSTEM))
        assertSame(base, LocaleHelper.contextualize(base, "xx"))
    }

    @Test
    fun contextualize_jaWraps() {
        val wrapped = LocaleHelper.contextualize(app(), LANGUAGE_JA)
        assertNotSame(app(), wrapped)
        assertEquals("キャンセル", wrapped.getString(R.string.cancel))
    }

    @Test
    fun getString_languages() {
        val base = app()
        // Unknown codes fall back to the base (system) resources.
        assertEquals(base.getString(R.string.cancel), LocaleHelper.getString(base, "xx", R.string.cancel))
        assertEquals("キャンセル", LocaleHelper.getString(base, LANGUAGE_JA, R.string.cancel))
        assertEquals("取消", LocaleHelper.getString(base, LANGUAGE_ZH, R.string.cancel))
        assertEquals("취소", LocaleHelper.getString(base, LANGUAGE_KO, R.string.cancel))
        // Formatted args pass through.
        assertEquals(
            base.getString(R.string.fcm_token, "abcdef123456"),
            LocaleHelper.getString(base, LANGUAGE_EN, R.string.fcm_token, "abcdef123456"),
        )
    }
}
