package org.example;

import java.time.LocalDate;

public class Event {
    private final LocalDate date;
    private final EventType type;
    private final double amount; //This is in USD
    private final double shares;

    //constructor
    public Event(LocalDate date, EventType type, double amount, double shares){
        this.date = date;
        this.type = type;
        this.amount = amount;
        this.shares = shares;
    }

    //to string
    @Override
    public String toString(){
        return String.format("%s, %s, %s, %s", date, type, shares, amount);
    }

    //getters for private fields
    public LocalDate getDate(){
        return date;
    }

    public EventType getType(){
        return type;
    }

    public double getAmount(){
        return amount;
    }

    public double getShares(){
        return shares;
    }
}

enum EventType {
    BUY, SELL, DIVIDEND, DEPOSIT, TAX, REINVEST, UNKNOWN
}
