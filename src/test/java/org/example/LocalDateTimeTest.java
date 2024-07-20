package org.example;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class LocalDateTimeTest {
    @Test
    void testLocalDateTime() {
        String dateStr = "Sep-29-2023";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM-dd-yyyy");
        LocalDate date = LocalDate.parse(dateStr, formatter);
        System.out.println("date = " + date);
    }
}
