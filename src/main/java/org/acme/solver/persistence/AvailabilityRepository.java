package org.acme.solver.persistence;


import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import org.acme.solver.model.Availability;

@ApplicationScoped
public class AvailabilityRepository implements PanacheRepository<Availability> {

}
