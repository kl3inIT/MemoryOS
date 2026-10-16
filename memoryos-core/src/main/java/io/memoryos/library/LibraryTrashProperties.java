package io.memoryos.library;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long a deleted file stays in the library's trash before its bytes are released (MEM-152 phase 4), for uploads
 * and for the files and images Chat generated alike. {@code 0} releases them at once. The key stays under
 * {@code memoryos.chat.retention}, where it was before the library left Chat; Chat's own retention settings bind the
 * rest of that prefix.
 */
@ConfigurationProperties("memoryos.chat.retention")
public record LibraryTrashProperties(@DefaultValue("30d") Duration trashAfter) {
    public LibraryTrashProperties {
        if (trashAfter == null || trashAfter.isNegative() || trashAfter.toDays() > 365) {
            throw new IllegalArgumentException("The file trash window must be between 0 and 365 days");
        }
    }
}
