package com.example;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class CoreTest {
    @Test
    void testGreet() {
        assertEquals("Hello, World!", new Core().greet("World"));
    }
}
