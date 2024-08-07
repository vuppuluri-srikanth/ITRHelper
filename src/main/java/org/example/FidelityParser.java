package org.example;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.example.dtos.*;
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
    static int calendarYear;
    public static LocalDate cyStartDate;
    public static LocalDate cyEndDate;
    static CurrencyConverter currencyConverter;
    private static LocalDate fyEndDate;
    private static LocalDate fyStartDate;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            //For FY 2023-24 provide 2023 as Accounting Year is 2023 Jan - Dec for US
            System.out.println("Usage: FidelityParser <Folder-Containing-Fidelity-Transaction-History> <Accounting Year>");
            return;
        }
        Path folderPath = Paths.get(args[0]);//folder containing fidelity transaction history
        String accountingYearStr = args[1];//accounting year
        calendarYear = Integer.parseInt(accountingYearStr);
        cyStartDate = LocalDate.of(calendarYear, 1, 1);
        cyEndDate = LocalDate.of(calendarYear, 12, 31);
        fyEndDate = LocalDate.of(calendarYear + 1, 3, 31);//Indian FY ends on 31st March
        fyStartDate = LocalDate.of(calendarYear, 4, 1);//Indian FY starts on 1st April

        List<Event> events = ReadAllEvents(folderPath);
        currencyConverter = CurrencyConverterFactory.build(USD, INR);
        List<Lot> lots = new ArrayList<>();
        Lot unmetESPPLot = null;
        for (Event event : events) {
            if (event.getDate().isAfter(fyEndDate))
                break;

            boolean beforeCY = event.getDate().isBefore(cyStartDate);
            boolean afterCY = event.getDate().isAfter(cyEndDate);
            boolean beforeFY = event.getDate().isBefore(fyStartDate);

            if (event.getType().equals(EventType.BUY) || event.getType().equals(EventType.DEPOSIT)) {
                //TODO: Get ticker from Data and remove hard coding
                double acquisitionPrice = currencyConverter.convert(event.getDate(), getInitialValuePerShareIn$(event, event.getDate()));
                Lot lot = new Lot(event.getDate(), event.getShares(), acquisitionPrice, event.getAmount(), !afterCY);
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
                        lot.decrementShares(numSharedSold, beforeCY);
                        // Only consider sales done in the FY
                        if (!beforeFY) {
                            lot.incrementSales(currencyConverter.convertIncome(event.getDate(), numSharedSold * saleAmountPerShare));
                            lot.incrementSharesSold(numSharedSold);
                        }
                        numShares -= numSharedSold;
                        if(numShares == 0.0)
                            break;
                    }
                }
            } else if (event.getType().equals(EventType.DIVIDEND)) {
                if (beforeFY)
                    continue;

                double amount = event.getAmount();
                double totalSharesNow = getTotalShares(lots);
                double dividendPerShare = amount / totalSharesNow;
                for (Lot lot : lots) {
                    if (lot.isActive()) {
                        lot.incrementDividends(currencyConverter.convertIncome(event.getDate(), lot.getNumShares() * dividendPerShare));
                    }
                }
            } else if (event.getType().equals(EventType.ESPP)) {
                if (unmetESPPLot == null)
                    throw new RuntimeException("No unmet ESPP lot found");
                if (unmetESPPLot.getAcquisitionCostIn$() == event.getAmount()) {
                    unmetESPPLot.setDateOfAcquiring(event.getDate());
                    unmetESPPLot.setActiveForCalendarYear(!afterCY);
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

        printEvents(events);
        int totalSale = printLongTermCapitalGains(lots);
        int totalSaleFA = printForeignAssets(lots);

        if (totalSale != totalSaleFA)
            throw new IllegalStateException(String.format("Total Sale is not matching : %d %d", totalSale, totalSaleFA));
    }

    private static void printEvents(List<Event> events) {
        printSales(events);
        Map<LocalDate, Double> taxEvents = printTaxes(events);
        printDividends(events, taxEvents);
    }

    private static void printSales(List<Event> events) {
        System.out.println("------------------------------- Sales ---------------------------------------");
        System.out.println("Date,Amount(Foreign Currency),Amount(INR)");
        double sales = 0;
        for(Event event : events){
            if(!event.getDate().isBefore(fyStartDate) && event.getType().equals(EventType.SELL) && event.getAmount() > 0){
                double amount = currencyConverter.convertIncome(event.getDate(), event.getAmount());
                sales += amount;
                System.out.printf("%s,%f,%.2f%n",event.getDate(), event.getAmount(), amount);
            }
        }
        System.out.printf("sales = %.2f%n", sales);
    }

    private static Map<LocalDate, Double> printTaxes(List<Event> events) {
        Map<LocalDate, Double> taxEvents = new HashMap<>();
        System.out.println("------------------------------- Taxes ---------------------------------------");
        System.out.println("Date,Amount(Foreign Currency),Amount(INR)");
        double taxes = 0;
        for(Event event : events){
            if(!event.getDate().isBefore(fyStartDate) && event.getType().equals(EventType.TAX) && event.getAmount() > 0){
                taxEvents.put(event.getDate(), event.getAmount());
                double amount = currencyConverter.convertIncome(event.getDate(), event.getAmount());
                taxes += amount;
                System.out.printf("%s,%f,%.2f%n",event.getDate(), event.getAmount(), amount);
            }
        }
        System.out.printf("Total taxes = %.2f%n", taxes);
        return taxEvents;
    }

    private static void printDividends(List<Event> events, Map<LocalDate, Double> taxEvents) {
        System.out.println("------------------------------- Dividends ---------------------------------------");
        System.out.println("Date,Amount(Foreign Currency),Amount(INR)");
        double dividendValue = 0;
        double[] dividendByQuarter = new double[5];
        TreeMap<LocalDate, Double> dividends = new TreeMap<>();
        for(Event event : events){
            if(!event.getDate().isBefore(fyStartDate) && event.getType().equals(EventType.DIVIDEND) && event.getAmount() > 0){
                if(taxEvents.containsKey(event.getDate())) {
                    double amount = currencyConverter.convertIncome(event.getDate(), event.getAmount());
                    dividendValue += amount;

                    if(event.getDate().isBefore(LocalDate.of(calendarYear, 6, 16)))
                        dividendByQuarter[0] += amount;
                    else if(event.getDate().isBefore(LocalDate.of(calendarYear, 9, 16)))
                        dividendByQuarter[1] += amount;
                    else if(event.getDate().isBefore(LocalDate.of(calendarYear, 12, 16)))
                        dividendByQuarter[2] += amount;
                    else if(event.getDate().isBefore(LocalDate.of(calendarYear + 1, 3, 16)))
                        dividendByQuarter[3] += amount;
                    else
                        dividendByQuarter[4] += amount;

                    System.out.printf("%s,%f,%.2f%n", event.getDate(), event.getAmount(), amount);
                    dividends.put(event.getDate(), amount);
                }
            }
        }
        System.out.printf("Total dividends = %.2f%n", dividendValue);
        for (int i = 0; i < 5; i++) {
            System.out.printf("Quarter %d : %.2f%n", i + 1, dividendByQuarter[i]);
        }

        printForm67(dividends);
    }

    private static void printForm67(TreeMap<LocalDate, Double> dividends) {
        int i = 1;
        System.out.println("------------------------------- Form 67 ---------------------------------------");
        System.out.println("Sl. No.,Name of the country/specified territory,Please specify,Source of income,Please specify ,Income from outside India,Amount,Rate(%),Tax payable on such income under normal provisions in India,Tax payable on such income under Section 115JB/JC,Article No. of Double Taxation Avoidance Agreements,Rate of tax as per Double Taxation Avoidance Agreements(%),Amount ,Credit claimed under section 91,Total foreign tax credit claimed");
        for(Map.Entry<LocalDate, Double> entry : dividends.entrySet()){
            double dividend = Math.round(entry.getValue());
            double tax = Math.round(dividend/4);
            double taxNormalProvisions = Math.round(dividend * 0.3);
            System.out.printf("%d,2,,7,,%d,%d,25,%d,,10,25,%d,0,%d%n",
                    i, (int)dividend, (int)tax, (int)taxNormalProvisions,(int)tax,(int)tax);
            i++;
        }
    }

    //Returns total sale value
    private static int printForeignAssets(List<Lot> lots) {
        System.out.println("------------------------------- Foreign Assets ---------------------------------------");
        System.out.println("Date, Initial Value, Peak Value, Closing value, Dividends, SaleAmount");
        List<TaxEntry> taxEntries = lots.stream().filter(Lot::isActiveForCalendarYear).map(FidelityParser::prepareTaxEntry).collect(Collectors.toList());
        Map<LocalDate, Optional<TaxEntry>> map = taxEntries.stream().collect(Collectors.groupingBy(TaxEntry::getDateOfAcquiring, Collectors.reducing(TaxEntry::sum)));
        map.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).map(Optional::get).forEach(t -> {
            validateTaxEntry(t);
            System.out.println(t);
        });
        return (int) map.values().stream().map(Optional::get).mapToDouble(TaxEntry::getSaleAmount).sum();
    }

    //TODO: Fix Opening, Closing and Peak values
    private static void validateTaxEntry(TaxEntry taxEntry) {
        if (taxEntry.getSaleAmount() > taxEntry.getPeakValue())
            throw new IllegalStateException("Sale amount is greater than peak value: " + taxEntry);
    }

    // Return total sale
    private static int printLongTermCapitalGains(List<Lot> lots) {
        double costOfAcquisition = 0;
        double saleValue = 0;
        for (Lot lot : lots) {
            if (lot.getSaleAmount() > 0) {
                //TODO: Cost of Acquisition has to be calculated for individual sale lots as Sale year should be considered for that lot and not Calendar Year
                costOfAcquisition += calculateValueWithIndexation(lot.getSharesSold() * lot.getAcquisitionPricePerShare(),
                        getCostInflation(lot.getDateOfAcquiring().getYear()), getCostInflation(calendarYear));
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
        double numShares = lot.getNumSharesInCalendarYear();

        int initialValue = (int) (lot.getAcquisitionPricePerShare() * numShares);
        int peakValue = (int) getPeakValue(dateOfAcquiring, numShares);
        int closingValue = (int) currencyConverter.convert(fyEndDate, getStockPrice(MSFT, fyEndDate) * numShares);

        return new TaxEntry(dateOfAcquiring, initialValue, peakValue, closingValue, (int) lot.getDividends(), lot.getSaleAmount());
    }

    private static double getTotalShares(List<Lot> lots) {
        return lots.stream().map(Lot::getNumShares).reduce(Double::sum).orElseThrow(() -> new RuntimeException("No lots found"));
    }

    private static double getPeakValue(LocalDate dateOfAcquiring, double numShares) {
        LocalDate startDate = fyStartDate;
        LocalDate endDate = fyEndDate;
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
                            if (event != null && !event.getDate().isAfter(fyEndDate)) {
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
                typeStr.contains("KKR") ||
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
        if (typeStr.equals("NON-RESIDENT TAX") || typeStr.equals("NON-RESIDENT TAX DIVIDEND RECEIVED"))
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