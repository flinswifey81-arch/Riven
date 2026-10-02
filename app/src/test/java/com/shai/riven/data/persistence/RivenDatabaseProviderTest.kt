package com.shai.riven.data.persistence

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RivenDatabaseProviderTest {
    @Test
    fun foregroundAndBackgroundLeasesShareOneOpenCanonicalDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RivenDatabase.DATABASE_NAME)
        val foreground = RivenDatabaseProvider.acquire(context)
        val background = RivenDatabaseProvider.acquire(context)

        try {
            assertSame(foreground.database, background.database)
            background.database.openHelper.writableDatabase
            foreground.close()
            assertTrue(background.database.isOpen)
        } finally {
            foreground.close()
            background.close()
        }

        assertFalse(background.database.isOpen)
        context.deleteDatabase(RivenDatabase.DATABASE_NAME)
    }
}
