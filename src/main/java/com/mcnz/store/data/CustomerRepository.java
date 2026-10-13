package com.mcnz.store.data;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mcnz.store.Customer;

/* http://localhost:8080/h2-console/ 
Check application.properties to verify the port

*/
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByName(String name);

    List<Customer> findByNameContainingIgnoreCase(String name);
}