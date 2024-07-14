package org.example.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@AllArgsConstructor
@Getter
public class Event {
    //getters for private fields
    private final LocalDate date;
    private final EventType type;
    private final double amount; //This is in USD
    private final double shares;
    private final int year;

    //to string
    @Override
    public String toString(){
        return String.format("Date: %s, Type: %s, Shares: %s, Amount: %s, Year: %s", date, type, shares, amount, year);
    }

}
