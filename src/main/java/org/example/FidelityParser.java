package org.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FidelityParser {
    static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM-dd-yyyy");
    static Map<LocalDate, Double> usdToInrMap;

    static Map<LocalDate, Double> stockPriceMap;
    static Map<Integer, Integer> costInflationIndexMap;

    static int accountingYear;
    static LocalDate startDate;
    static LocalDate ayEndDate;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: FidelityParser <Folder-Containing-Fidelity-Transaction-History> <Accounting-Year>");
            return;
        }
        Path folderPath = Paths.get(args[0]);//folder containing fidelity transaction history
        String accountingYearStr = args[1];//accounting year
        accountingYear = Integer.parseInt(accountingYearStr);
        startDate = LocalDate.of(accountingYear, 1, 1);
        ayEndDate = LocalDate.of(accountingYear, 12, 31);
        LocalDate fyEndDate = LocalDate.of(accountingYear + 1, 3, 31);//Indian FY ends on 31st March. This will be used only for Dividend Tax calculation
        LocalDate fyStartDate = LocalDate.of(accountingYear, 4, 1);//Indian FY ends on 31st March. This will be used only for Dividend Tax calculation

        List<Event> events = ReadAllEvents(folderPath);

        usdToInrMap = GetCurrencyConversionMap(folderPath);
        stockPriceMap = GetMsftPriceMap(folderPath);
        costInflationIndexMap = GetCostInflationIndexMap();
        List<Lot> lots = new ArrayList<>();
        Lot unmetESPPLot = null;
        for (Event event : events) {
            if (event.getDate().isAfter(ayEndDate))
                break;

            boolean beforeAY = event.getDate().isBefore(startDate);
            double conversionFactor = getConversionFactor(usdToInrMap, event.getDate());
            if (event.getType().equals(EventType.BUY) || event.getType().equals(EventType.DEPOSIT)) {
                Lot lot = new Lot(event.getDate(), event.getShares(),
                        getInitialValuePerShareIn$(event, stockPriceMap, event.getDate()) * conversionFactor,
                        event.getAmount());
                lots.add(lot);
                if (event.getType().equals(EventType.BUY)) {
                    unmetESPPLot = lot;
                }
            } else if (event.getType().equals(EventType.SELL)) {
                double numShares = -1 * event.getShares();
                double saleAmount = event.getAmount();
                double saleAmountPerShare = saleAmount / numShares;

                for (Lot lot : lots) {
                    if (lot.isActive()) {
                        double numSharesInLot = lot.getNumShares();
                        if (numSharesInLot >= numShares) {
                            lot.decrementShares(numShares, beforeAY);
                            lot.incrementSales(saleAmount * conversionFactor);
                            lot.incrementSharesSold(numShares);
                            break;
                        } else {
                            lot.decrementShares(numSharesInLot, beforeAY);
                            lot.incrementSales(numSharesInLot * saleAmountPerShare * conversionFactor);
                            lot.incrementSharesSold(numSharesInLot);
                            numShares -= numSharesInLot;
                        }
                    }
                }
            } else if (event.getType().equals(EventType.DIVIDEND)) {
                if (beforeAY)
                    continue; //consider dividends only in AY

                double amount = event.getAmount();
                double totalSharesNow = getTotalShares(lots);
                double dividendPerShare = amount / totalSharesNow;
                for (Lot lot : lots) {
                    if (lot.isActive()) {
                        lot.incrementDividends(lot.getNumShares() * dividendPerShare * getConversionFactor(usdToInrMap, event.getDate()));
                    }
                }
            } else if (event.getType().equals(EventType.ESPP)) {
                if (unmetESPPLot == null)
                    throw new RuntimeException("No unmet ESPP lot found");
                if (unmetESPPLot.getAcquisitionCostIn$() == event.getAmount()) {
                    unmetESPPLot.setDateOfAcquiring(event.getDate());
                    unmetESPPLot = null;
                } else {
                    throw new RuntimeException("ESPP lot amount mismatch");
                }
            }
        }
        if (unmetESPPLot != null) {
            System.out.println("Unmet ESPP lot found : " + unmetESPPLot);
            lots.remove(unmetESPPLot);
        }

        printLongTermCapitalGains(lots);

        printIncomeFromDividends(fyEndDate, fyStartDate, events);

        PrintForeignAssets(lots);

        //print lots
//        System.out.println("Date, NumSharesAtAcquisition, AcquisitionPricePerShare, NumSharesInAccountingYear, Dividends, SaleAmount");
//        lots.stream().filter(Lot::isActiveForAccountingYear).forEach(System.out::println);
    }


    private static void printIncomeFromDividends(LocalDate fyEndDate, LocalDate fyStartDate, List<Event> events) {
        double dividendForFY = 0; //India FY
        double taxCollectedOutside = 0;
        for (Event event : events) {
            if (event.getDate().isBefore(fyStartDate))
                continue;
            if (event.getDate().isAfter(fyEndDate))
                break;

            if (event.getType().equals(EventType.DIVIDEND)) {
                dividendForFY += event.getAmount() * getConversionFactor(usdToInrMap, event.getDate());
            } else if (event.getType().equals(EventType.TAX)) {
                taxCollectedOutside += event.getAmount() * getConversionFactor(usdToInrMap, event.getDate());
            }
        }
        System.out.println("------------------------------- Dividend Income ---------------------------------------");
        System.out.println("Dividend Income, Tax Collected Outside India");
        System.out.println((int) dividendForFY + ", " + (int) taxCollectedOutside);
    }

    private static void PrintForeignAssets(List<Lot> lots) throws JsonProcessingException {
        System.out.println("------------------------------- Foreign Assets ---------------------------------------");
        System.out.println("Date, Initial Value, Peak Value, Closing value, Dividends, SaleAmount");
        List<TaxEntry> taxEntries = lots.stream().filter(Lot::isActiveForAccountingYear).map(FidelityParser::prepareTaxEntry).collect(Collectors.toList());
        Map<LocalDate, Optional<TaxEntry>> map = taxEntries.stream().collect(Collectors.groupingBy(TaxEntry::getDateOfAcquiring, Collectors.reducing(TaxEntry::sum)));
        map.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).map(Map.Entry::getValue).map(Optional::get).forEach(System.out::println);
    }

    private static void printLongTermCapitalGains(List<Lot> lots) {
        double costOfAcquisition = 0;
        double saleValue = 0;
        for (Lot lot : lots) {
            if (lot.isActiveForAccountingYear() && lot.getSaleAmount() > 0) {
                costOfAcquisition += calculateValueWithIndexation(lot.getSharesSold() * lot.getAcquisitionPricePerShare(),
                        costInflationIndexMap.get(lot.getDateOfAcquiring().getYear()), costInflationIndexMap.get(accountingYear));
                saleValue += lot.getSaleAmount();
            }
        }
        System.out.println("------------------------------- Long Term Capital Gains ---------------------------------------");
        System.out.println("Cost of Acquisition with Indexation, Sale Value");
        System.out.println((int) costOfAcquisition + ", " + (int) saleValue);
    }

    private static double calculateValueWithIndexation(double value, int baseIndex, int desiredIndex) {
        return value * desiredIndex / baseIndex;
    }

    private static Map<Integer, Integer> GetCostInflationIndexMap() throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(FidelityParser.class.getResourceAsStream("/cost_inflation_index.csv"))))) {
            Map<Integer, Integer> costInflationIndexMap = new HashMap<>();
            String line;
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split(",");
                costInflationIndexMap.put(Integer.parseInt(tokens[0].split("-")[0]), Integer.parseInt(tokens[1]));
            }
            return costInflationIndexMap;
        }
    }

    private static TaxEntry prepareTaxEntry(Lot lot) {
        LocalDate dateOfAcquiring = lot.getDateOfAcquiring();
        double numShares = lot.getNumSharesInAccountingYear();

        int initialValue = (int) (lot.getAcquisitionPricePerShare() * numShares);
        int peakValue = (int) getPeakValue(stockPriceMap, dateOfAcquiring, accountingYear, numShares, usdToInrMap);
        int closingValue = (int) (getStockPrice(stockPriceMap, ayEndDate) * getConversionFactor(usdToInrMap, ayEndDate) * numShares);

        return new TaxEntry(dateOfAcquiring, initialValue, peakValue, closingValue, (int) lot.getDividends(), (int) lot.getSaleAmount());
    }

    private static double getTotalShares(List<Lot> lots) {
        return lots.stream().map(Lot::getNumShares).reduce(Double::sum).orElseThrow(() -> new RuntimeException("No lots found"));
    }

    private static TreeMap<LocalDate, Double> GetMsftPriceMap(Path folderPath) {
        TreeMap<LocalDate, Double> msftPriceMap = new TreeMap<>();
        Path usdToInrPath = Paths.get(folderPath.toString(), "MSFT.tsv");

        try (Stream<String> lines = Files.lines(usdToInrPath)) {
            CustomForEach.forEach(lines, (elem, breaker) -> {
                if (StringUtils.isBlank(elem)) {
                    breaker.stop();
                } else {
                    String[] tokens = elem.split("\t");
                    if (!tokens[0].equals("Date") && tokens.length == 7) {
                        LocalDate localDate = LocalDate.parse(tokens[0], DateTimeFormatter.ofPattern("MMM dd, yyyy"));
                        double price = Double.parseDouble(tokens[4]);
                        msftPriceMap.put(localDate, price);
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        return msftPriceMap;
    }

    private static double getConversionFactor(Map<LocalDate, Double> usdToInrMap, LocalDate dateOfAcquiring) {
        return getConversionFactor(usdToInrMap, dateOfAcquiring, 2);
    }

    private static double getConversionFactor(Map<LocalDate, Double> usdToInrMap, LocalDate dateOfAcquiring, int fallbackDays) {
        if (!usdToInrMap.containsKey(dateOfAcquiring)) {
            if (fallbackDays == 0)
                throw new RuntimeException("No USD to INR conversion rate found for date: " + dateOfAcquiring);

            //System.out.println("Falling back by 1 day for Conversion Factor");
            return getConversionFactor(usdToInrMap, dateOfAcquiring.minusDays(1), fallbackDays - 1);
        }
        return usdToInrMap.get(dateOfAcquiring);
    }

    private static double getPeakValue(Map<LocalDate, Double> stockPriceMap, LocalDate dateOfAcquiring, int accountingYear, double numShares, Map<LocalDate, Double> usdToInrMap) {
        LocalDate startDate = LocalDate.of(accountingYear, 1, 1);
        LocalDate endDate = LocalDate.of(accountingYear, 12, 31);
        if (!dateOfAcquiring.isBefore(startDate))
            startDate = dateOfAcquiring;
        if (dateOfAcquiring.isAfter(endDate))
            throw new RuntimeException("Date of acquiring is after the accounting year: " + dateOfAcquiring);

        Pair<LocalDate, Double> peakPriceAndDate = getPeakStockPrice(stockPriceMap, startDate, endDate);
        double conversionFactor = getConversionFactor(usdToInrMap, peakPriceAndDate.getLeft());
        return peakPriceAndDate.getRight() * conversionFactor * numShares;
    }

    private static Pair<LocalDate, Double> getPeakStockPrice(Map<LocalDate, Double> stockPriceMap, LocalDate startDate, LocalDate endDate) {
        List<Map.Entry<LocalDate, Double>> applicableEntries = stockPriceMap.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(startDate) && !e.getKey().isAfter(endDate))
                .collect(Collectors.toList());
        //reverse sort by value
        applicableEntries.sort(Comparator.comparing(Map.Entry::getValue, Comparator.reverseOrder()));
        return Pair.of(applicableEntries.get(0).getKey(), applicableEntries.get(0).getValue());
    }

    private static double getInitialValuePerShareIn$(Event event, Map<LocalDate, Double> stockPriceMap, LocalDate dateOfAcquiring) {
        if (event.getType().equals(EventType.BUY)) {
            return event.getAmount() / event.getShares();
        } else if (event.getType().equals(EventType.DEPOSIT)) {
            return getStockPrice(stockPriceMap, dateOfAcquiring);
        } else {
            throw new RuntimeException("Unknown event type: " + event.getType());
        }
    }

    private static double getStockPrice(Map<LocalDate, Double> stockPriceMap, LocalDate dateOfAcquiring) {
        return getStockPrice(stockPriceMap, dateOfAcquiring, 2);
    }

    private static double getStockPrice(Map<LocalDate, Double> stockPriceMap, LocalDate dateOfAcquiring, int fallbackDays) {
        if (!stockPriceMap.containsKey(dateOfAcquiring)) {
            if (fallbackDays == 0)
                throw new RuntimeException("No stock price found for date: " + dateOfAcquiring);

//            System.out.println("Falling back by 1 day for Stock Price");
            return getStockPrice(stockPriceMap, dateOfAcquiring.minusDays(1), fallbackDays - 1);
        }

        return stockPriceMap.get(dateOfAcquiring);
    }

    private static List<Event> ReadAllEvents(Path folderPath) throws IOException {
        List<Event> events = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folderPath)) {
            stream.forEach(path -> {
                if (!path.toString().contains("Transaction history"))
                    return;
                try (Stream<String> lines = Files.lines(path)) {
                    AtomicReference<Event> unmetESPP = new AtomicReference<>();
                    CustomForEach.forEach(lines, (elem, breaker) -> {
                        elem = elem.replaceAll("\\uFEFF", "");
                        if (StringUtils.isBlank(elem)) {
                            breaker.stop();
                        } else {
                            Event event = FidelityParser.parseLine(elem, unmetESPP.get());
                            if (event != null) {
                                events.add(event);
                                if (event.getType().equals(EventType.BUY)) {
                                    unmetESPP.set(event);
                                }
                            }
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
        return events.stream().sorted(Comparator.comparing(Event::getDate)).collect(Collectors.toList());
    }

    private static Map<LocalDate, Double> GetCurrencyConversionMap(Path folderPath) {
        Map<LocalDate, Double> usdToInrMap = new HashMap<>();
        Path usdToInrPath = Paths.get(folderPath.toString(), "USDINR.csv");

        try (Stream<String> lines = Files.lines(usdToInrPath)) {
            CustomForEach.forEach(lines, (elem, breaker) -> {
                if (StringUtils.isBlank(elem)) {
                    breaker.stop();
                } else {
                    String[] tokens = elem.split(",");
                    if (!tokens[0].equals("Date")) {
                        LocalDate localDate = LocalDate.parse(tokens[0], DateTimeFormatter.ofPattern("MM-dd-yyyy"));
                        double usdToInr = Double.parseDouble(tokens[4]);
                        usdToInrMap.put(localDate, usdToInr);
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        return usdToInrMap;
    }

    private static Event parseLine(String line, Event unmetESPPEvent) {
        if (line.startsWith("Transaction date")) {
            return null;
        }
//        System.out.println("Line: " + line);
        String[] tokens = line.split(",");

        String dateStr = tokens[0].trim();
        LocalDate date = LocalDate.parse(dateStr, formatter);
        String typeStr = tokens[1].trim();
        EventType type = getEventType(typeStr);
        String investmentName = tokens[2].trim();
        String numShares = tokens[3].trim();
        numShares = numShares.equals("-") ? "0" : numShares;

        String amountStr = tokens[4].trim().replaceAll("\\$", "");
        double amount = Double.parseDouble(amountStr);
        if (amount < 0) {
            amount = -1 * amount;
        }

        if (type.equals(EventType.REINVEST) || investmentName.equals("FIDELITY GOVERNMENT CASH RESERVES") ||
                typeStr.equals("JOURNALED WIRE/CHECK FEE") || typeStr.equals("JOURNALED CASH WITHDRAWAL")) {
            return null;
        }

        if (EventType.UNKNOWN.equals(type)) {
            throw new RuntimeException("Unknown event type: " + typeStr);
        }
        double shares = Double.parseDouble(numShares);
        return new Event(date, type, amount, shares);
    }

    private static EventType getEventType(String typeStr) {
        if (typeStr.equals("DIVIDEND RECEIVED"))
            return EventType.DIVIDEND;
        if (typeStr.equals("NON-RESIDENT TAX"))
            return EventType.TAX;
        if (typeStr.equals("CONVERSION SHARES DEPOSITED"))
            return EventType.DEPOSIT;
        if (typeStr.startsWith("YOU BOUGHT ESPP"))
            return EventType.BUY;
        if (typeStr.startsWith("YOU SOLD"))
            return EventType.SELL;
        if (typeStr.startsWith("REINVESTMENT REINVEST"))
            return EventType.REINVEST;
        if (typeStr.startsWith("JOURNALED SPP PURCHASE CREDIT"))
            return EventType.ESPP;
        return EventType.UNKNOWN;
    }
}