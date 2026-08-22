package io.memoryos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void acceptsOnlyStableUppercaseFailureCodes() {
        assertEquals(
                "INVITATION_CONFLICT",
                new TestBusinessException("INVITATION_CONFLICT").code()
        );
        assertThrows(IllegalArgumentException.class, () -> {
            throw new TestBusinessException("invitation_conflict");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            throw new TestBusinessException("1_INVITATION_CONFLICT");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            throw new TestBusinessException("INVITATION CONFLICT");
        });
    }
}

final class TestBusinessException extends BusinessException {

    TestBusinessException(String code) {
        super(
                code,
                FailureCategory.CONFLICT,
                "The operation conflicts with existing state.",
                "test diagnostic"
        );
    }
}
