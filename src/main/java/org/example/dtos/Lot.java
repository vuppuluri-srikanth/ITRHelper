package org.example.dtos;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

//All amounts in this are in INR
@Getter
public class Lot {
    @Setter
    private LocalDate dateOfAcquiring;
    private final double numSharesAtAcquisition;
    private final double acquisitionPricePerShare;
    private double numSharesInAccountingYear;
    private boolean activeForAccountingYear = true;
    private double numShares;
    private boolean active = true;
    private double dividends;
    private double saleAmount;

    private double sharesSold;
    private final double acquisitionCostIn$;

    public Lot(LocalDate dateOfAcquiring, double numSharesAtAcquisition, double acquisitionPrice, double acquisitionCostIn$){
        this.dateOfAcquiring = dateOfAcquiring;
        this.numSharesAtAcquisition = numSharesAtAcquisition;
        this.acquisitionPricePerShare = acquisitionPrice;
        this.numSharesInAccountingYear = numSharesAtAcquisition;
        this.numShares = numSharesAtAcquisition;
        this.dividends = 0;
        this.saleAmount = 0;
        this.sharesSold = 0;
        this.acquisitionCostIn$ = acquisitionCostIn$;
    }

    //to string
    @Override
    public String toString(){
        return String.format("%s, %s, %s, %s, %s, %s", dateOfAcquiring, numSharesAtAcquisition, acquisitionPricePerShare, numSharesInAccountingYear, dividends, saleAmount);
    }

    public void incrementSharesSold(double sharesSold){
        this.sharesSold += sharesSold;
    }

    private void decrementSharesInAY(double numShares){
        if(numSharesInAccountingYear < numShares)
            throw new RuntimeException("Cannot decrement more shares than present in the lot");
        this.numSharesInAccountingYear -= numShares;
        if(this.numSharesInAccountingYear == 0)
            this.activeForAccountingYear = false;
    }

    public void incrementDividends(double dividends){
        this.dividends += dividends;
    }

    public void incrementSales(double saleAmount){
        this.saleAmount += saleAmount;
    }

    public void decrementShares(double numShares, boolean beforeAY){
        if(this.numShares < numShares)
            throw new RuntimeException("Cannot decrement more shares than present in the lot");
        this.numShares -= numShares;
        if(this.numShares == 0)
            this.active = false;

        if(beforeAY){
            decrementSharesInAY(numShares);
        }
    }
}
