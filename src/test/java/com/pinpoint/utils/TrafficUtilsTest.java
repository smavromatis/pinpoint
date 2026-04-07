package com.pinpoint.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TrafficUtilsTest {

    @Test
    public void testParseHostAndPath_ValidUrl() {
        String[] result = TrafficUtils.parseHostAndPath("https://api.github.com/v3/users");
        assertEquals("api.github.com", result[0]);
        assertEquals("/v3/users", result[1]);
    }

    @Test
    public void testParseHostAndPath_InvalidUrl() {
        String[] result = TrafficUtils.parseHostAndPath("://bad url with spaces");
        assertEquals("-", result[0]);
        assertEquals("/", result[1]);
    }

    @Test
    public void testGetFileType_Endpoints() {
        assertEquals("Endpoints", TrafficUtils.getFileType("/api/login"));
        assertEquals("Endpoints", TrafficUtils.getFileType("/index.php"));
    }

    @Test
    public void testGetFileType_ImagesAndFonts() {
        assertEquals("Images", TrafficUtils.getFileType("/assets/logo.png"));
        assertEquals("Images", TrafficUtils.getFileType("/images/bg.webp?v=123"));
        assertEquals("Fonts", TrafficUtils.getFileType("/fonts/inter.woff2"));
    }
}
