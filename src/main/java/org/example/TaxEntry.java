package org.example;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

//The amounts in this are all Rupees
public class TaxEntry {
    private final LocalDate dateOfAcquiring;
    private final int initialValue;

    private final int peakValue;
    private final int closingValue;

    private final int amountCredited;
    private final int saleAmount;

    //constructor
    public TaxEntry(LocalDate dateOfAcquiring, int initialValue, int peakValue, int closingValue, int amountCredited, int saleAmount){
        this.dateOfAcquiring = dateOfAcquiring;
        this.initialValue = initialValue;
        this.peakValue = peakValue;
        this.closingValue = closingValue;
        this.amountCredited = amountCredited;
        this.saleAmount = saleAmount;
    }

    //to string
    @Override
    public String toString(){
        return String.format("%s, %d, %d, %d, %d, %d", dateOfAcquiring.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), initialValue, peakValue, closingValue, amountCredited, saleAmount);
    }


    public int getPeakValue() {
        return peakValue;
    }

    public int getInitialValue() {
        return initialValue;
    }

    public LocalDate getDateOfAcquiring() {
        return dateOfAcquiring;
    }

    public int getClosingValue() {
        return closingValue;
    }

    public int getAmountCredited() {
        return amountCredited;
    }

    public int getSaleAmount() {
        return saleAmount;
    }
}
