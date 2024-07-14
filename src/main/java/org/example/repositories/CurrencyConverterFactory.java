package org.example.repositories;

import org.example.dtos.Currency;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;

public class CurrencyConverterFactory {
    private static final Map<String, Map<LocalDate, Double>> currencyMap = new HashMap<>();
    public static final int FALLBACK_DAYS = 2;

    //for now assuming from is always USD and to is INR
    private static Map<LocalDate, Double> getCurrencyMap(Currency from, Currency to) {
        String key = String.format("%s%s", from, to);

        if (currencyMap.containsKey(key))
            return currencyMap.get(key);

        Map<LocalDate, Double> dateToRateMap = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                StockPriceRepository.class.getResourceAsStream(String.format("/currency_conversions/%s.csv", key)))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split(",");
                if (!tokens[0].equals("Date")) {
                    LocalDate localDate = LocalDate.parse(tokens[0], DateTimeFormatter.ofPattern("MM-dd-yyyy"));
                    double usdToInr = Double.parseDouble(tokens[4]);
                    dateToRateMap.put(localDate, usdToInr);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Exception reading Conversion Rate. Exception : " + e);
        } catch (NullPointerException e) {
            dateToRateMap = null;
        }
        currencyMap.put(key, dateToRateMap);
        return currencyMap.get(key);
    }

    public static CurrencyConverter build(Currency from, Currency to){
        String key = String.format("%s%s", from, to);
        Map<LocalDate, Double> prices = getCurrencyMap(from, to);
        if(prices == null)
            throw new MissingResourceException("Stock Prices not found for currencies", "Currencies", key);

        return new CurrencyConverter(from, to);
    }

    public static class CurrencyConverter{

        private final Map<LocalDate, Double> map;

        public CurrencyConverter(Currency from, Currency to){
            this.map = currencyMap.get(String.format("%s%s", from, to));
        }

        public double convert(LocalDate date, double amount) {
            int i = 0;
            while(i <= FALLBACK_DAYS){
                if(map.containsKey(date))
                    return map.get(date) * amount;
                date = date.minusDays(1);
                i++;
            }

            throw new MissingResourceException("No Currency Conversion found for date", "Currency", date.toString());
        }
    }
}
