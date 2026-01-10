package org.acme.solver.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.model.Employee;

@ApplicationScoped
public class EmployeeRepository implements PanacheRepository<Employee> {

}
