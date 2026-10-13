package com.mcnz.store.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mcnz.store.Address;
import com.mcnz.store.Customer;
import com.mcnz.store.Product;
import com.mcnz.store.Purchase;
import com.mcnz.store.PurchaseStatus;
import com.mcnz.store.data.CustomerRepository;
import com.mcnz.store.data.ProductRepository;
import com.mcnz.store.data.PurchaseRepository;


@Service
public class StoreService {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private ProductRepository productRepository;

    private int purchaseRows;
    private int fullRefunds;
    private int partialRefunds;
    private int unmatchedRefunds;

    @Transactional
    public Purchase createPendingPurchase(Purchase sourcePurchase) {
        Customer customer = customerRepository.findByName(sourcePurchase.customer.name).orElse(null);

        if (customer == null) {
            customer = customerRepository.save(
                new Customer(sourcePurchase.customer.name, sourcePurchase.customer.address)
            );
        }

        Purchase pendingPurchase = new Purchase(sourcePurchase.date, customer);
        pendingPurchase.status = PurchaseStatus.PENDING;

        for (Product product : sourcePurchase.products) {
            pendingPurchase.addProduct(new Product(product.name, product.quantity, product.price));
        }

        customer.addPurchase(pendingPurchase);
        return purchaseRepository.save(pendingPurchase);
    }

    @Transactional
    public Purchase createPendingPurchase(Long customerId, Long productId, int quantity) {
        Customer customer = customerRepository.findById(customerId)
            .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        Purchase pendingPurchase = new Purchase(LocalDate.now(), customer);
        pendingPurchase.status = PurchaseStatus.PENDING;
        pendingPurchase.addProduct(new Product(product.name, quantity, product.price));
        customer.addPurchase(pendingPurchase);
        return purchaseRepository.save(pendingPurchase);
    }

    @Transactional
    public Purchase updatePurchaseStatus(Long purchaseId, PurchaseStatus status) {
        Long id = java.util.Objects.requireNonNull(purchaseId);
        Purchase purchase = purchaseRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Purchase not found: " + id));
        purchase.status = status;
        return purchaseRepository.save(purchase);
    }

    @Transactional(readOnly = true)
    public PurchaseStatus getPurchaseStatus(Long purchaseId) {
        Long id = java.util.Objects.requireNonNull(purchaseId);
        return purchaseRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Purchase not found: " + id))
                .status;
    }

    @Transactional(readOnly = true)
    public Purchase getPurchase(Long purchaseId) {
        Long id = java.util.Objects.requireNonNull(purchaseId);
        return purchaseRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Purchase not found: " + id));
    }

    
    @Transactional
    public Purchase processPurchase(
            LocalDate date,
            String customerName,
            String zip,
            String city,
            String productName,
            int quantity,
            double price) {

        Customer customer = customerRepository.findByName(customerName).orElse(null);
        Purchase purchase = null;

        if (customer == null) {
            customer = customerRepository.save(
                new Customer(
                    customerName,
                    new Address(zip, city)
                )
            );
        }

        for (Purchase candidate : customer.purchases) {
            if (candidate.date.equals(date)) {
                purchase = candidate;
                break;
            }
        }

        if (purchase == null) {
            purchase = new Purchase(date, customer);
            customer.addPurchase(purchase);
        }

        purchase.status = PurchaseStatus.COMPLETED;

        purchase.addProduct(
            new Product(productName, quantity, price)
        );

        customerRepository.save(customer);
        purchaseRows++;

        return purchase;
    }

    @Transactional
    public void processRefund(
            String customerName,
            String productName,
            int refundQuantity) {

        Customer customer = customerRepository.findByName(customerName).orElse(null);

        if (customer == null) {
            refundNotFound(customerName, productName, refundQuantity);
            return;
        }

        Product product = null;
        LocalDate latestDate = null;

        for (Purchase purchase : customer.purchases) {
            for (Product candidate : purchase.products) {
                if (!candidate.name.equals(productName)) {
                    continue;
                }

                if (latestDate == null || purchase.date.isAfter(latestDate)) {
                    latestDate = purchase.date;
                    product = candidate;
                }
            }
        }

        if (product == null) {
            refundNotFound(customerName, productName, refundQuantity);
            return;
        }

        if (refundQuantity < product.quantity) {

            product.quantity -= refundQuantity;
            customerRepository.save(customer);

            partialRefunds++;

            System.out.printf(
                "PARTIAL REFUND: %s - %s, remaining quantity %d%n",
                customerName,
                productName,
                product.quantity
            );

            return;
        }

        Purchase purchase = product.purchase;

        purchase.removeProduct(product);

        if (purchase.products.isEmpty()) {
            customer.removePurchase(purchase);
        }

        customerRepository.save(customer);

        fullRefunds++;

        System.out.printf(
            "FULL REFUND: %s - %s removed%n",
            customerName,
            productName
        );
    }

    private void refundNotFound(
            String customerName,
            String productName,
            int refundQuantity) {

        unmatchedRefunds++;
        System.out.printf(
            "REFUND NOT FOUND: %s - %s x%d%n",
            customerName,
            productName,
            refundQuantity
        );
    }

    @Transactional(readOnly = true)
    public void printReport() {

        System.out.println();
        System.out.println("========================================");
        System.out.println("STORE IMPORT REPORT");
        System.out.println("========================================");
        System.out.println("Purchase rows:       " + purchaseRows);
        System.out.println("Full refunds:        " + fullRefunds);
        System.out.println("Partial refunds:     " + partialRefunds);
        System.out.println("Unmatched refunds:   " + unmatchedRefunds);

        System.out.println();
        System.out.println("========================================");
        System.out.println("CURRENT PURCHASES");
        System.out.println("========================================");

        double grandTotal = 0.0;

        List<Customer> customers = customerRepository.findAll();

        for (Customer customer : customers) {

            System.out.printf(
                "%n%s - %s, %s%n",
                customer.name,
                customer.address.city,
                customer.address.zip
            );


            for (Purchase purchase : customer.purchases) {

                System.out.println("  " + purchase.date);

                for (Product product : purchase.products) {

                    double lineTotal = product.quantity * product.price;
                    grandTotal += lineTotal;

                    System.out.printf(
                        "    %-28s x%-2d @ $%7.2f = $%8.2f%n",
                        product.name,
                        product.quantity,
                        product.price,
                        lineTotal
                    );
                }
            }
        }

        System.out.println();
        System.out.printf("CURRENT STORE TOTAL: $%.2f%n", grandTotal);
        System.out.println("========================================");
    }
}
