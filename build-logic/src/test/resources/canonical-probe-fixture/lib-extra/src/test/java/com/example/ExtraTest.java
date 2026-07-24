package com.example;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class ExtraTest {
    @Test
    void testEnhance() {
        assertEquals("enhanced:test", new Extra().enhance("test"));
    }
}
