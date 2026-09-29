package com.shai.riven.data.archive

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RivenBackupPolicyTest {
    @Test
    fun manifestDisablesAndroidAutoBackup() {
        val manifest = source("src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }

    @Test
    fun modernDataExtractionRulesExcludeAllCanonicalDomainsForCloudAndTransfer() {
        val xml = source("src/main/res/xml/data_extraction_rules.xml")
        val cloud = xml.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = xml.substringAfter("<device-transfer>").substringBefore("</device-transfer>")

        listOf("database", "file", "sharedpref", "root").forEach { domain ->
            assertTrue(cloud.contains("<exclude domain=\"$domain\" path=\".\" />"))
            assertTrue(transfer.contains("<exclude domain=\"$domain\" path=\".\" />"))
        }
    }

    @Test
    fun legacyBackupRulesExcludeAllCanonicalDomains() {
        val xml = source("src/main/res/xml/backup_rules.xml")

        listOf("database", "file", "sharedpref", "root").forEach { domain ->
            assertTrue(xml.contains("<exclude domain=\"$domain\" path=\".\" />"))
        }
    }

    private fun source(relativeToApp: String): String {
        val candidates = listOf(File(relativeToApp), File("app", relativeToApp))
        return checkNotNull(candidates.firstOrNull(File::isFile)) {
            "Missing source policy file: $relativeToApp"
        }.readText()
    }
}
