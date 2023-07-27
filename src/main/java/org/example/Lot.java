package org.example;

import java.time.LocalDate;

//All amounts in this are in INR
public class Lot {
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

    public LocalDate getDateOfAcquiring() {
        return dateOfAcquiring;
    }

    public void incrementSharesSold(double sharesSold){
        this.sharesSold += sharesSold;
    }

    public double getNumSharesAtAcquisition() {
        return numSharesAtAcquisition;
    }

    public double getAcquisitionPricePerShare() {
        return acquisitionPricePerShare;
    }

    public double getNumSharesInAccountingYear() {
        return numSharesInAccountingYear;
    }

    private void decrementSharesInAY(double numShares){
        if(numSharesInAccountingYear < numShares)
            throw new RuntimeException("Cannot decrement more shares than present in the lot");
        this.numSharesInAccountingYear -= numShares;
        if(this.numSharesInAccountingYear == 0)
            this.activeForAccountingYear = false;
    }

    public boolean isActiveForAccountingYear() {
        return activeForAccountingYear;
    }

    public double getDividends() {
        return dividends;
    }

    public void incrementDividends(double dividends){
        this.dividends += dividends;
    }

    public double getSaleAmount() {
        return saleAmount;
    }

    public void incrementSales(double saleAmount){
        this.saleAmount += saleAmount;
    }

    public boolean isActive() {
        return active;
    }

    public double getNumShares() {
        return numShares;
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

    public double getSharesSold() {
        return sharesSold;
    }

    public double getAcquisitionCostIn$() {
        return acquisitionCostIn$;
    }

    public void setDateOfAcquiring(LocalDate date) {
        this.dateOfAcquiring = date;
    }
}
