package org.example.repositories;

import org.apache.commons.lang3.tuple.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class StockPriceRepository {
    private static final Map<String, TreeMap<LocalDate, Double>> stockPrices = new HashMap<>();
    public static final int FALLBACK_DAYS = 2;

    public static Double getStockPrice(String ticker, LocalDate date){
        Map<LocalDate, Double> prices = getStockPrices(ticker);
        if(prices == null)
            throw new MissingResourceException("Stock Prices not found for ticker", "Stock", ticker);

        LocalDate tempDate = LocalDate.parse(date.toString());
        int i = 0;
        while(i <= FALLBACK_DAYS){
            if(prices.containsKey(tempDate))
                return prices.get(tempDate);
            tempDate = tempDate.minusDays(1);
            i++;
        }

        throw new RuntimeException("No stock price found for date: " + date);
    }

    //TODO: this can be improved
    public static Pair<LocalDate, Double> getPeakStockPrice(String ticker, LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, Double> prices = getStockPrices(ticker);
        if(prices == null)
            throw new MissingResourceException("Stock Prices not found for ticker", "Stock", ticker);

        List<Map.Entry<LocalDate, Double>> applicableEntries = prices.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(startDate) && !e.getKey().isAfter(endDate))
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toList());
        return Pair.of(applicableEntries.get(0).getKey(), applicableEntries.get(0).getValue());
    }

    // For now this tracks the Closing price
    private static Map<LocalDate, Double> getStockPrices(String ticker) {
        if(stockPrices.containsKey(ticker))
            return stockPrices.get(ticker);

        TreeMap<LocalDate, Double> treeMap = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                StockPriceRepository.class.getResourceAsStream(String.format("/stock_prices/%s.tsv", ticker)))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split("\t");
                if (!tokens[0].equals("Date") && tokens.length == 7) {
                    LocalDate localDate = LocalDate.parse(tokens[0], DateTimeFormatter.ofPattern("MMM dd, yyyy"));
                    double price = Double.parseDouble(tokens[4]);
                    treeMap.put(localDate, price);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Exception reading stock prices. Exception : " + e.toString());
        } catch (NullPointerException e){
            treeMap = null;
        }
        stockPrices.put(ticker, treeMap);
        return stockPrices.get(ticker);
    }
}
