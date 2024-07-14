package org.example;

import org.example.repositories.StockPriceRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;

import static org.example.FidelityParser.MSFT;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StockPriceRepositoryTest {

    @Test
    void getStockPrices() throws IOException {
        assertEquals(241.73, StockPriceRepository.getStockPrice(MSFT, LocalDate.parse("2022-11-16")));
    }
}
