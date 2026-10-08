package io.github.yuroyami.audiokodec.io

import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class FileHostTest {
    @Test
    fun unavailableFilesAndHostsHaveNamedFailures() {
        val failure = assertFails { Path(".audiokodec-absent-${Random.nextLong().toULong().toString(16)}").openAudioSource() }
        assertTrue(failure is IOException || failure is UnsupportedFeatureException)
        if (failure is UnsupportedFeatureException) assertTrue(failure.feature.contains("seekable files"))
    }
}
