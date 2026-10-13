package com.mcnz.store;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;



@Entity
public class Product {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;
    public int quantity;
    public double price;

    
    @ManyToOne
    @JsonIgnore
    public Purchase purchase;

    public Product() {
    }

    public Product(String name, int quantity, double price) {
        this.name = name;
        this.quantity = quantity;
        this.price = price;
    }
}
