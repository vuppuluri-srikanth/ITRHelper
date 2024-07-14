package org.example.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

//The amounts in this are all Rupees
@Getter
@AllArgsConstructor
public class TaxEntry {
    private final LocalDate dateOfAcquiring;
    private final int initialValue;
    private final int peakValue;
    private final int closingValue;
    private final int amountCredited;
    private final double saleAmount;

    @Override
    public String toString(){
        return String.format("%s, %d, %d, %d, %d, %d", dateOfAcquiring.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                initialValue, peakValue, closingValue, amountCredited, (int) saleAmount);
    }

    public static TaxEntry sum(TaxEntry taxEntry1, TaxEntry taxEntry2) {
        return new TaxEntry(taxEntry1.getDateOfAcquiring(),
                taxEntry1.getInitialValue() + taxEntry2.getInitialValue(),
                taxEntry1.getPeakValue() + taxEntry2.getPeakValue(),
                taxEntry1.getClosingValue() + taxEntry2.getClosingValue(),
                taxEntry1.getAmountCredited() + taxEntry2.getAmountCredited(),
                taxEntry1.getSaleAmount() + taxEntry2.getSaleAmount());
    }
}
