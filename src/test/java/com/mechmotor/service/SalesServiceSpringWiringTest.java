package com.mechmotor.service;

import com.mechmotor.repository.PartRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class SalesServiceSpringWiringTest {

    @Test
    void springCanInstantiateSalesServiceUsingProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("jdbcTemplate", mock(JdbcTemplate.class));
            context.getBeanFactory().registerSingleton("partRepository", mock(PartRepository.class));
            context.getBeanFactory().registerSingleton("transactionManager", mock(PlatformTransactionManager.class));
            context.register(SalesService.class);
            context.refresh();

            assertNotNull(context.getBean(SalesService.class));
        }
    }
}
