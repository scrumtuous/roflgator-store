package com.mcnz.store;

import jakarta.persistence.Embeddable;



@Embeddable
public class Address {

    public String zip;
    public String city;

    public Address() {
    }

    public Address(String zip, String city) {
        this.zip = zip;
        this.city = city;
    }
}
