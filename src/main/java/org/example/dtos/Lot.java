package org.example.dtos;

import lombok.*;

import java.time.LocalDate;

//All amounts in this are in INR
@Getter
public class Lot {
    @Setter
    private LocalDate dateOfAcquiring;
    private final double numSharesAtAcquisition;
    private final double acquisitionPricePerShare;
    private double numSharesInCalendarYear;
    @Setter
    private boolean activeForCalendarYear;
    private double numShares;
    private boolean active = true;
    private double dividends;
    private double saleAmount;

    private double sharesSold;
    private final double acquisitionCostIn$;

    public Lot(LocalDate dateOfAcquiring, double numSharesAtAcquisition, double acquisitionPrice, double acquisitionCostIn$,
               boolean activeForCalendarYear){
        this.dateOfAcquiring = dateOfAcquiring;
        this.numSharesAtAcquisition = numSharesAtAcquisition;
        this.acquisitionPricePerShare = acquisitionPrice;
        this.numSharesInCalendarYear = numSharesAtAcquisition;
        this.numShares = numSharesAtAcquisition;
        this.dividends = 0;
        this.saleAmount = 0;
        this.sharesSold = 0;
        this.acquisitionCostIn$ = acquisitionCostIn$;
        this.activeForCalendarYear = activeForCalendarYear;
    }

    //to string
    @Override
    public String toString(){
        return String.format("%s, %s, %s, %s, %s, %s", dateOfAcquiring, numSharesAtAcquisition, acquisitionPricePerShare, numSharesInCalendarYear, dividends, saleAmount);
    }

    public void incrementSharesSold(double sharesSold){
        this.sharesSold += sharesSold;
    }

    private void decrementSharesInCY(double numShares){
        if(!activeForCalendarYear){
            System.out.println("Warning: decrementSharesInCY called when not supposed to");
            return;
        }

        if(numSharesInCalendarYear < numShares)
            throw new RuntimeException("Cannot decrement more shares than present in the lot");
        this.numSharesInCalendarYear -= numShares;
        if(this.numSharesInCalendarYear == 0)
            this.activeForCalendarYear = false;
    }

    public void incrementDividends(double dividends){
        this.dividends += dividends;
    }

    public void incrementSales(double saleAmount){
        if(saleAmount == 0.0)
            throw new RuntimeException("Amount is zero");
        this.saleAmount += saleAmount;
    }

    public void decrementShares(double numShares, boolean beforeCY){
        if(this.numShares < numShares)
            throw new RuntimeException("Cannot decrement more shares than present in the lot");
        this.numShares -= numShares;
        if(this.numShares == 0)
            this.active = false;

        if(beforeCY){
            decrementSharesInCY(numShares);
        }
    }
}

