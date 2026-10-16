package com.mechmotor.service;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LicenseFeatureAspectTest {
    @Test
    void classFeatureGuardBlocksExecutionWhenLicenseRejectsFeature() {
        LicenseService license = mock(LicenseService.class);
        doThrow(new IllegalStateException("feature not licensed")).when(license).requireFeature("SALES");
        AtomicBoolean executed = new AtomicBoolean();
        ClassProtectedService target = new ClassProtectedService(executed);

        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new LicenseFeatureAspect(license));
        ClassProtectedService proxy = factory.getProxy();

        assertThrows(IllegalStateException.class, proxy::perform);
        assertFalse(executed.get());
        verify(license).requireFeature("SALES");
    }

    @Test
    void methodFeatureGuardAllowsExecutionOnlyAfterFeatureCheck() {
        LicenseService license = mock(LicenseService.class);
        AtomicBoolean executed = new AtomicBoolean();
        MethodProtectedService target = new MethodProtectedService(executed);

        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new LicenseFeatureAspect(license));
        MethodProtectedService proxy = factory.getProxy();

        assertEquals("done", proxy.perform());
        assertTrue(executed.get());
        verify(license).requireFeature("CSV");
    }

    @RequiresFeature("SALES")
    static class ClassProtectedService {
        private final AtomicBoolean executed;
        ClassProtectedService(AtomicBoolean executed) { this.executed = executed; }
        public void perform() { executed.set(true); }
    }

    static class MethodProtectedService {
        private final AtomicBoolean executed;
        MethodProtectedService(AtomicBoolean executed) { this.executed = executed; }
        @RequiresFeature("CSV")
        public String perform() { executed.set(true); return "done"; }
    }
}
