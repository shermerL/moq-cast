package com.example.moqandroid

import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionResultTest {
    @Test
    fun emptyPermissionResultIsCancellation() {
        assertFalse(permissionsGranted(intArrayOf()))
    }

    @Test
    fun everyPermissionMustBeGranted() {
        assertTrue(permissionsGranted(intArrayOf(PackageManager.PERMISSION_GRANTED)))
        assertFalse(
            permissionsGranted(
                intArrayOf(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED),
            ),
        )
    }
}
