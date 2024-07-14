package org.example.repositories;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

public class CostInflationRepository {
    private static Map<Integer, Integer> costInflationMap;

    public static int getCostInflation(int year){
        Map<Integer, Integer> map = getCostInflationMap();
        if(map.containsKey(year))
            return map.get(year);

        throw new MissingResourceException("No Cost Inflation Index found for Year", "Cost Inflation", String.valueOf(year));
    }


    // For now this tracks the Closing price
    private static Map<Integer, Integer> getCostInflationMap() {
        if(costInflationMap != null)
            return costInflationMap;

        costInflationMap = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                StockPriceRepository.class.getResourceAsStream("/cost_inflation_index.csv"))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split(",");
                costInflationMap.put(Integer.parseInt(tokens[0].split("-")[0]), Integer.parseInt(tokens[1]));
            }
        } catch (IOException e) {
            throw new RuntimeException("Exception reading stock prices. Exception : " + e.toString());
        } catch (NullPointerException e){
            throw new MissingResourceException("Cost Inflation Data missing", "Cost Inflation", "File");
        }
        return costInflationMap;
    }
}
