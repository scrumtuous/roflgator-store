package com.mcnz.store.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mcnz.store.Customer;
import com.mcnz.store.Product;
import com.mcnz.store.Purchase;
import com.mcnz.store.data.CustomerRepository;
import com.mcnz.store.data.ProductRepository;
import com.mcnz.store.data.PurchaseRepository;
import com.mcnz.store.service.StoreService;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class StoreRestController {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private StoreService storeService;

    @GetMapping("/products")
    public Product product(@RequestParam(required = false) String name,
            @RequestParam(required = false) Long id) {

        if (name != null && !name.isBlank()) {
            List<Product> matches = productRepository.findByNameContainingIgnoreCase(name);
            if (!matches.isEmpty()) {
                return matches.get(0);
            }
        }

        if (id != null) {
            return productRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide name or id");
    }

    @GetMapping("/customers")
    public Customer customer(@RequestParam(required = false) String name,
            @RequestParam(required = false) Long id) {

        if (name != null && !name.isBlank()) {
            List<Customer> matches = customerRepository.findByNameContainingIgnoreCase(name);
            if (!matches.isEmpty()) {
                return matches.get(0);
            }
        }

        if (id != null) {
            return customerRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide name or id");
    }

    @GetMapping("/purchases")
    public Purchase purchase(@RequestParam long id) {
        return purchaseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
    }

    @GetMapping("/report")
    public String report() {
        storeService.printReport();
        return "Report printed in the application console";
    }

    @PostMapping("/sales")
    public Purchase addSale(
            @RequestParam LocalDate date,
            @RequestParam String customerName,
            @RequestParam String zip,
            @RequestParam String city,
            @RequestParam String productName,
            @RequestParam int quantity,
            @RequestParam double price) {

        return storeService.processPurchase(date, customerName, zip, city, productName, quantity, price);
    }
}