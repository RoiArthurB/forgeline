package fr.arthurbrugiere.forgeline.core.forge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PkceTest {
    @Test
    fun the_challenge_matches_rfc_7636_appendix_b() {
        assertThat(Pkce.of("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").challenge).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun a_generated_verifier_is_long_enough_and_url_safe() {
        val verifier = Pkce.generate().verifier

        // RFC 7636: 43 to 128 characters from the unreserved set.
        assertThat(verifier.length).isIn(43..128)
        assertThat(verifier).matches("[A-Za-z0-9._~-]+")
    }
}
