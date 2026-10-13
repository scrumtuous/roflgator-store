package com.mcnz.store;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;



@Entity
public class Customer {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;

    
    @Embedded
    public Address address;


    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonIgnore
    public List<Purchase> purchases = new ArrayList<>();

    public Customer() {
    }

    public Customer(String name, Address address) {
        this.name = name;
        this.address = address;
    }

    public void addPurchase(Purchase purchase) {
        purchases.add(purchase);
        purchase.customer = this;
    }

    public void removePurchase(Purchase purchase) {
        purchases.remove(purchase);
        purchase.customer = null;
    }
}
