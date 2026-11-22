package com.renan.renanmusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun comparaVersoes() {
        assertTrue(Updater.newer("0.0.12", "0.0.11"))
        assertTrue(Updater.newer("0.1.0", "0.0.99"))
        assertTrue(Updater.newer("1.0", "0.9.9"))
        assertFalse(Updater.newer("0.0.11", "0.0.11"))
        assertFalse(Updater.newer("0.0.9", "0.0.10"))
    }
}
