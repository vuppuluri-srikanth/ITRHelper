package org.example.dtos;

import lombok.Data;

@Data
public class DtlsForeignEquityDebtInterest {
    private final String AddressOfEntity = "One Microsoft Way Redmond, WA";
    private final int ClosingBalance;
    private final String CountryCodeExcludingIndia = "2";
    private final String CountryName = "2-United States Of America";
    private final int InitialValOfInvstmnt;
    private final String InterestAcquiringDate;
    private final String NameOfEntity = "Microsoft";
    private final String NatureOfEntity = "Company listed on stock exchange";
    private final int PeakBalanceDuringPeriod;
    private final int TotGrossAmtPaidCredited;
    private final int TotGrossProceeds;
    private final String ZipCode = "98052";
}
