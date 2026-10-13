package com.mcnz.store;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;



@Entity
public class Purchase {

    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public LocalDate date;

    
    @ManyToOne
    public Customer customer;

    @Enumerated(EnumType.STRING)
    public PurchaseStatus status;


    @OneToMany(mappedBy = "purchase", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    public List<Product> products = new ArrayList<>();

    public Purchase() {
    }

    public Purchase(LocalDate date, Customer customer) {
        this.date = date;
        this.customer = customer;
    }

    public void addProduct(Product product) {
        products.add(product);
        product.purchase = this;
    }

    public void removeProduct(Product product) {
        products.remove(product);
        product.purchase = null;
    }
}
