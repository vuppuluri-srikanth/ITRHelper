package org.example;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.example.dtos.Event;
import org.example.dtos.EventType;
import org.example.dtos.Lot;
import org.example.dtos.TaxEntry;
import org.example.repositories.CurrencyConverterFactory;
import org.example.repositories.CurrencyConverterFactory.CurrencyConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.example.dtos.Currency.INR;
import static org.example.dtos.Currency.USD;
import static org.example.repositories.CostInflationRepository.getCostInflation;
import static org.example.repositories.StockPriceRepository.getPeakStockPrice;
import static org.example.repositories.StockPriceRepository.getStockPrice;

public class FidelityParser {
    static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM-dd-yyyy");

    public static final String MSFT = "MSFT";
    static int accountingYear;
    static LocalDate startDate;
    static LocalDate ayEndDate;
    static CurrencyConverter currencyConverter;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: FidelityParser <Folder-Containing-Fidelity-Transaction-History> <Financial-Year-Start>");//For FY 2023-24 provide 2023
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
        currencyConverter = CurrencyConverterFactory.build(USD, INR);
        List<Lot> lots = new ArrayList<>();
        Lot unmetESPPLot = null;
        for (Event event : events) {
            if (event.getDate().isAfter(ayEndDate))
                break;

            boolean beforeAY = event.getDate().isBefore(startDate);
            if (event.getType().equals(EventType.BUY) || event.getType().equals(EventType.DEPOSIT)) {
                //TODO: Get ticker from Data and remove hard coding
                double acquisitionPrice = currencyConverter.convert(event.getDate(), getInitialValuePerShareIn$(event, event.getDate()));
                Lot lot = new Lot(event.getDate(), event.getShares(), acquisitionPrice, event.getAmount());
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
                        double numSharedSold = Math.min(numSharesInLot, numShares);
                        lot.decrementShares(numSharedSold, beforeAY);
                        // Sales done before AY should make into the Sale Amount for AY
                        if (!beforeAY) {
                            lot.incrementSales(currencyConverter.convert(event.getDate(), numSharedSold * saleAmountPerShare));
                            lot.incrementSharesSold(numSharedSold);
                        }
                        numShares -= numSharedSold;
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
                        lot.incrementDividends(currencyConverter.convert(event.getDate(), lot.getNumShares() * dividendPerShare));
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

        int totalSale = printLongTermCapitalGains(lots);

        printIncomeFromDividends(fyEndDate, fyStartDate, events);

        int totalSaleFA = printForeignAssets(lots);
        if (totalSale != totalSaleFA)
            throw new IllegalStateException(String.format("Total Sale is not matching : %d %d", totalSale, totalSaleFA));
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
                dividendForFY += currencyConverter.convert(event.getDate(), event.getAmount());
            } else if (event.getType().equals(EventType.TAX)) {
                taxCollectedOutside += currencyConverter.convert(event.getDate(), event.getAmount());
            }
        }
        System.out.println("------------------------------- Dividend Income ---------------------------------------");
        System.out.println("Dividend Income, Tax Collected Outside India");
        System.out.println((int) dividendForFY + ", " + (int) taxCollectedOutside);
    }

    //Returns total sale value
    private static int printForeignAssets(List<Lot> lots) {
        System.out.println("------------------------------- Foreign Assets ---------------------------------------");
        System.out.println("Date, Initial Value, Peak Value, Closing value, Dividends, SaleAmount");
        List<TaxEntry> taxEntries = lots.stream().filter(Lot::isActiveForAccountingYear).map(FidelityParser::prepareTaxEntry).collect(Collectors.toList());
        Map<LocalDate, Optional<TaxEntry>> map = taxEntries.stream().collect(Collectors.groupingBy(TaxEntry::getDateOfAcquiring, Collectors.reducing(TaxEntry::sum)));
        map.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).map(Optional::get).forEach(t -> {
            validateTaxEntry(t);
            System.out.println(t);
        });
        return (int) map.values().stream().map(Optional::get).mapToDouble(TaxEntry::getSaleAmount).sum();
    }

    private static void validateTaxEntry(TaxEntry taxEntry) {
        if (taxEntry.getSaleAmount() > taxEntry.getPeakValue())
            throw new IllegalStateException("Sale amount is greater than peak value: " + taxEntry);
    }

    // Return total sale
    private static int printLongTermCapitalGains(List<Lot> lots) {
        double costOfAcquisition = 0;
        double saleValue = 0;
        for (Lot lot : lots) {
            if (lot.isActiveForAccountingYear() && lot.getSaleAmount() > 0) {
                costOfAcquisition += calculateValueWithIndexation(lot.getSharesSold() * lot.getAcquisitionPricePerShare(),
                        getCostInflation(lot.getDateOfAcquiring().getYear()), getCostInflation(accountingYear));
                saleValue += lot.getSaleAmount();
            }
        }
        System.out.println("------------------------------- Long Term Capital Gains ---------------------------------------");
        System.out.println("Cost of Acquisition with Indexation, Sale Value");
        System.out.println((int) costOfAcquisition + ", " + (int) saleValue);
        return (int) saleValue;
    }

    private static double calculateValueWithIndexation(double value, int baseIndex, int desiredIndex) {
        return value * desiredIndex / baseIndex;
    }

    private static TaxEntry prepareTaxEntry(Lot lot) {
        LocalDate dateOfAcquiring = lot.getDateOfAcquiring();
        double numShares = lot.getNumSharesInAccountingYear();

        int initialValue = (int) (lot.getAcquisitionPricePerShare() * numShares);
        int peakValue = (int) getPeakValue(dateOfAcquiring, accountingYear, numShares);
        int closingValue = (int) currencyConverter.convert(ayEndDate, getStockPrice(MSFT, ayEndDate) * numShares);

        return new TaxEntry(dateOfAcquiring, initialValue, peakValue, closingValue, (int) lot.getDividends(), lot.getSaleAmount());
    }

    private static double getTotalShares(List<Lot> lots) {
        return lots.stream().map(Lot::getNumShares).reduce(Double::sum).orElseThrow(() -> new RuntimeException("No lots found"));
    }

    private static double getPeakValue(LocalDate dateOfAcquiring, int accountingYear, double numShares) {
        LocalDate startDate = LocalDate.of(accountingYear, 1, 1);
        LocalDate endDate = LocalDate.of(accountingYear, 12, 31);
        if (!dateOfAcquiring.isBefore(startDate))
            startDate = dateOfAcquiring;
        if (dateOfAcquiring.isAfter(endDate))
            throw new RuntimeException("Date of acquiring is after the accounting year: " + dateOfAcquiring);

        Pair<LocalDate, Double> peakPriceAndDate = getPeakStockPrice(MSFT, startDate, endDate);
        return currencyConverter.convert(peakPriceAndDate.getLeft(), peakPriceAndDate.getRight() * numShares);
    }

    private static double getInitialValuePerShareIn$(Event event, LocalDate dateOfAcquiring) {
        if (event.getType().equals(EventType.BUY)) {
            return event.getAmount() / event.getShares();
        } else if (event.getType().equals(EventType.DEPOSIT)) {
            return getStockPrice(MSFT, dateOfAcquiring);
        } else {
            throw new RuntimeException("Unknown event type: " + event.getType());
        }
    }

    private static List<Event> ReadAllEvents(Path folderPath) throws IOException {
        List<Event> events = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folderPath)) {
            stream.forEach(path -> {
                if (!path.toString().contains("Transaction history"))
                    return;
                int year = Integer.parseInt(path.getFileName().toString().split(" ")[2].split("\\.")[0]);
                try (Stream<String> lines = Files.lines(path)) {
                    AtomicReference<Event> unmetESPP = new AtomicReference<>();
                    CustomForEach.forEach(lines, (elem, breaker) -> {
                        elem = elem.replaceAll("\\uFEFF", "");
                        if (StringUtils.isBlank(elem)) {
                            breaker.stop();
                        } else {
                            Event event = FidelityParser.parseLine(elem, unmetESPP.get(), year);
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

    private static Event parseLine(String line, Event unmetESPPEvent, int year) {
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

        if (type.equals(EventType.REINVEST) ||
                // TODO: Dividend whether it is against MICROSOFT CORP or FIDELITY GOVERNMENT CASH RESERVES should be treated as Dividend
                // However there is no Tax collected by Fidelity on the dividends deposited against FIDELITY GOVERNMENT CASH RESERVES
                investmentName.equals("FIDELITY GOVERNMENT CASH RESERVES") ||
                typeStr.equals("JOURNALED WIRE/CHECK FEE") || typeStr.equals("JOURNALED CASH WITHDRAWAL")) {
            return null;
        }

        if (EventType.UNKNOWN.equals(type)) {
            throw new RuntimeException("Unknown event type: " + typeStr);
        }
        double shares = Double.parseDouble(numShares);
        return new Event(date, type, amount, shares, year);
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