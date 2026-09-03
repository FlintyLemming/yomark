package com.dama.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmokeTest {
    @Test
    fun `test infrastructure runs`() {
        assertThat(1 + 1).isEqualTo(2)
    }
}
