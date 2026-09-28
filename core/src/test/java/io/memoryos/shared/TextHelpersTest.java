package io.memoryos.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextHelpersTest {
    @Test
    void sha256IsTheLowercaseHexOfTheUtf8Bytes() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"));
        assertEquals("89e897e1ebef3c2e40690d5083b8ad08c9ea77e5baf73c1591e76597a926ccd0", Sha256.hex("họp"));
    }

    @Test
    void aLikeSearchTakesItsWildcardsAndItsEscapeLiterally() {
        assertEquals("100\\%", LikePattern.escape("100%"));
        assertEquals("%a\\_b\\\\c%", LikePattern.containing("a_b\\c"));
    }
}
